"""
Builds the canonical material world, renders CPSE-style raw text records, and generates
a leakage-safe, labeled PAIR dataset for training/evaluating the matching model.

Usage:
    python generate_dataset.py --seed 42 --variants-per-canonical 4 --max-per-category 250

Relationship taxonomy produced (matches the strategy doc's equivalence-reasoning design):
    EXACT_DUPLICATE        - same canonical, normalized text identical
    NEAR_DUPLICATE         - same canonical, different surface text
    FUNCTIONALLY_EQUIVALENT- different canonical, curated cross-standard equivalence
    VARIANT                - same family, differs only in a "variant_critical" attribute
    NOT_A_MATCH             - different family OR differs in an "identity_critical" attribute
    NEEDS_REVIEW            - a critical attribute is missing on one side; can't decide safely
"""
import argparse
import itertools
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pandas as pd

from data_generation.schemas import CATEGORIES, CPSES, EQUIVALENCE_RULES
from data_generation.text_variation import TextVariationEngine

PIPE_GRADE_COMPAT = {"CS": ["GrB", "GrA"], "SS": ["TP304", "TP316"], "GI": ["GrB"], "PVC": ["PVC-U"]}


def build_canonical_materials(max_per_category: int, rng: random.Random):
    """Returns list of dicts: {canonical_id, category, attrs}."""
    canonicals = []
    cid = 0
    for category, schema in CATEGORIES.items():
        attrs_domain = schema["attrs"]
        keys = list(attrs_domain.keys())

        if category == "PIPE":
            combos = []
            for material in attrs_domain["material"]:
                for grade in PIPE_GRADE_COMPAT[material]:
                    for size in attrs_domain["nominal_size_mm"]:
                        for sch in attrs_domain["schedule"]:
                            combos.append({"material": material, "grade": grade,
                                           "nominal_size_mm": size, "schedule": sch})
        else:
            value_lists = [attrs_domain[k] for k in keys]
            combos = [dict(zip(keys, vals)) for vals in itertools.product(*value_lists)]

        if len(combos) > max_per_category:
            combos = rng.sample(combos, max_per_category)

        for combo in combos:
            # attach a random non-critical detail (standard / manufacturer) if the schema has one
            if "standard_domain" in schema:
                combo = {**combo, "standard": rng.choice(schema["standard_domain"])}
            if category in ("BEARING", "MOTOR", "PUMP", "FILTER"):
                combo = {**combo, "manufacturer": rng.choice(["SKF", "ABB", "CRI", "KSB", "Generic"])}
            if category == "MOTOR":
                combo = {**combo, "enclosure": rng.choice(["IP55", "IP44"])}
            canonicals.append({"canonical_id": f"CM_{category}_{cid:05d}",
                                "category": category, "attrs": combo})
            cid += 1
    return canonicals


def add_equivalence_canonicals(canonicals, rng: random.Random, n_per_rule: int = 15):
    """Explicitly manufactures matched twin canonicals for each curated equivalence rule:
    same underlying (non-overridden) attributes, differing only in the rule's override keys
    (typically the standard). Returns the new canonicals plus the list of (id_a, id_b) twin
    pairs -- generating twins directly avoids relying on the general random combinatorial
    sample happening to contain a same-spec pair under both standards."""
    new_canonicals = []
    twin_pairs = []
    counter = 0
    for rule in EQUIVALENCE_RULES:
        cat = rule["category"]
        schema = CATEGORIES[cat]
        override_keys = set(rule["a"].keys()) | set(rule["b"].keys())
        non_override_keys = [k for k in schema["attrs"].keys() if k not in override_keys]
        for _ in range(n_per_rule):
            base = {k: rng.choice(schema["attrs"][k]) for k in non_override_keys}
            ids = {}
            for side_label, override in (("A", rule["a"]), ("B", rule["b"])):
                attrs = {**base, **override}
                if cat in ("BEARING", "MOTOR", "PUMP", "FILTER"):
                    attrs["manufacturer"] = rng.choice(["SKF", "ABB", "CRI", "KSB", "Generic"])
                if cat == "MOTOR":
                    attrs["enclosure"] = rng.choice(["IP55", "IP44"])
                cid = f"CM_{cat}_EQ{counter:05d}{side_label}"
                counter += 1
                new_canonicals.append({"canonical_id": cid, "category": cat, "attrs": attrs})
                ids[side_label] = cid
            twin_pairs.append((ids["A"], ids["B"]))
    return new_canonicals, twin_pairs


def render_raw_records(canonicals, variants_per_canonical: int, engine: TextVariationEngine,
                        rng: random.Random):
    """Returns a DataFrame of raw CPSE-style text records, one row per rendered variant."""
    rows = []
    for cm in canonicals:
        n = variants_per_canonical
        # Guarantee at least one EXACT_DUPLICATE pair per canonical: render one variant
        # twice with identical style/unit/no-noise (simulates literal re-ingestion of the
        # same source description by a second CPSE, which does happen in practice).
        base_style, base_unit = rng.choice(["TERSE", "VERBOSE", "LEGACY", "PROCUREMENT"]), rng.choice(["mm", "inch"])
        desc0, spec0 = engine.render(cm["category"], cm["attrs"], style=base_style,
                                      unit_style=base_unit, include_noise=False)
        for k in range(n):
            omit = set()
            # ~12% chance to omit ONE non-critical attribute (missing-attribute variation)
            schema = CATEGORIES[cm["category"]]
            if rng.random() < 0.12 and schema.get("non_critical"):
                cand = [a for a in schema["non_critical"] if a in cm["attrs"]]
                if cand:
                    omit.add(rng.choice(cand))
            if k == 0:
                desc, spec = desc0, spec0
            elif k == 1 and rng.random() < 0.15:
                desc, spec = desc0, spec0  # deliberate literal duplicate -> EXACT_DUPLICATE case
            else:
                desc, spec = engine.render(cm["category"], cm["attrs"], omit_attrs=omit)
            rows.append({
                "canonical_id": cm["canonical_id"],
                "category": cm["category"],
                "cpse_name": rng.choice(CPSES),
                "cpse_material_code": f"{cm['category'][:3]}-{rng.randint(1000,99999)}",
                "description": desc,
                "specification": spec,
                "attrs_json": json.dumps(cm["attrs"]),
                "omitted_attrs_json": json.dumps(sorted(omit)),
            })
    return pd.DataFrame(rows)


def find_neighbors(canonicals, rng: random.Random, max_neighbors_per_canonical: int = 2):
    """For each canonical, find other canonicals in the same category differing in exactly
    one attribute -- used to build VARIANT / NOT_A_MATCH hard pairs."""
    by_category = {}
    for cm in canonicals:
        by_category.setdefault(cm["category"], []).append(cm)

    lookup = {}
    for cat, items in by_category.items():
        for cm in items:
            key = (cat, tuple(sorted(cm["attrs"].items())))
            lookup[key] = cm["canonical_id"]

    neighbor_pairs = []  # (cm_a, cm_b, differing_attr)
    for cat, items in by_category.items():
        for cm in items:
            found = []
            attr_keys = list(cm["attrs"].keys())
            rng.shuffle(attr_keys)
            for attr in attr_keys:
                if attr in ("standard", "manufacturer", "enclosure"):
                    continue  # non_critical, not used for neighbor generation
                domain = CATEGORIES[cat]["attrs"].get(attr)
                if not domain:
                    continue
                other_vals = [v for v in domain if v != cm["attrs"][attr]]
                rng.shuffle(other_vals)
                for v in other_vals:
                    new_attrs = dict(cm["attrs"])
                    new_attrs[attr] = v
                    if cat == "PIPE" and attr in ("material", "grade"):
                        # keep material/grade jointly valid
                        if attr == "material" and new_attrs["grade"] not in PIPE_GRADE_COMPAT[v]:
                            new_attrs["grade"] = PIPE_GRADE_COMPAT[v][0]
                        if attr == "grade" and v not in PIPE_GRADE_COMPAT[new_attrs["material"]]:
                            continue
                    key = (cat, tuple(sorted(new_attrs.items())))
                    other_id = lookup.get(key)
                    if other_id and other_id != cm["canonical_id"]:
                        found.append((cm["canonical_id"], other_id, attr))
                        break
                if len(found) >= max_neighbors_per_canonical:
                    break
            neighbor_pairs.extend(found)
    return neighbor_pairs, {cm["canonical_id"]: cm for cm in canonicals}


def build_pairs(raw_df: pd.DataFrame, canonicals, neighbor_pairs, twin_pairs, rng: random.Random,
                 max_positive_per_canonical=6, n_random_negatives=6000,
                 n_cross_category_negatives=2000, n_needs_review=1500):
    by_canonical = {cid: g for cid, g in raw_df.groupby("canonical_id")}
    cm_by_id = {cm["canonical_id"]: cm for cm in canonicals}
    pairs = []

    def normtext(s):
        return "".join(ch for ch in s.upper() if ch.isalnum())

    # 1) positive pairs (EXACT_DUPLICATE / NEAR_DUPLICATE)
    for cid, group in by_canonical.items():
        rows = group.to_dict("records")
        combos = list(itertools.combinations(range(len(rows)), 2))
        rng.shuffle(combos)
        for i, j in combos[:max_positive_per_canonical]:
            r1, r2 = rows[i], rows[j]
            label = "EXACT_DUPLICATE" if normtext(r1["description"]) == normtext(r2["description"]) else "NEAR_DUPLICATE"
            pairs.append(_pair_row(r1, r2, label))

    # 2) neighbor pairs -> VARIANT or NOT_A_MATCH
    for cid_a, cid_b, attr in neighbor_pairs:
        if cid_a not in by_canonical or cid_b not in by_canonical:
            continue
        r1 = by_canonical[cid_a].sample(1, random_state=rng.randint(0, 999999)).iloc[0].to_dict()
        r2 = by_canonical[cid_b].sample(1, random_state=rng.randint(0, 999999)).iloc[0].to_dict()
        cat = cm_by_id[cid_a]["category"]
        label = "NOT_A_MATCH" if attr in CATEGORIES[cat]["identity_critical"] else "VARIANT"
        pairs.append(_pair_row(r1, r2, label))

    # 3) functional-equivalence pairs -- directly from the manufactured twin pairs (see
    # add_equivalence_canonicals), so "equivalence" only ever differs in the rule's
    # override keys, never silently also differs in size/pressure/etc.
    for cid_a, cid_b in twin_pairs:
        if cid_a not in by_canonical or cid_b not in by_canonical:
            continue
        for _ in range(min(len(by_canonical[cid_a]), len(by_canonical[cid_b]), 3)):
            r1 = by_canonical[cid_a].sample(1, random_state=rng.randint(0, 999999)).iloc[0].to_dict()
            r2 = by_canonical[cid_b].sample(1, random_state=rng.randint(0, 999999)).iloc[0].to_dict()
            pairs.append(_pair_row(r1, r2, "FUNCTIONALLY_EQUIVALENT"))

    # 4) random negatives, same category (easy-to-hard mix)
    cat_groups = {}
    for cm in canonicals:
        cat_groups.setdefault(cm["category"], []).append(cm["canonical_id"])
    for _ in range(n_random_negatives):
        cat = rng.choice(list(cat_groups.keys()))
        ids = cat_groups[cat]
        if len(ids) < 2:
            continue
        cid_a, cid_b = rng.sample(ids, 2)
        if cid_a not in by_canonical or cid_b not in by_canonical:
            continue
        r1 = by_canonical[cid_a].sample(1, random_state=rng.randint(0, 999999)).iloc[0].to_dict()
        r2 = by_canonical[cid_b].sample(1, random_state=rng.randint(0, 999999)).iloc[0].to_dict()
        pairs.append(_pair_row(r1, r2, "NOT_A_MATCH"))

    # 5) cross-category negatives (obviously different)
    all_ids_by_cat = cat_groups
    cats = list(all_ids_by_cat.keys())
    for _ in range(n_cross_category_negatives):
        cat_a, cat_b = rng.sample(cats, 2)
        cid_a = rng.choice(all_ids_by_cat[cat_a])
        cid_b = rng.choice(all_ids_by_cat[cat_b])
        r1 = by_canonical[cid_a].iloc[0].to_dict()
        r2 = by_canonical[cid_b].iloc[0].to_dict()
        pairs.append(_pair_row(r1, r2, "NOT_A_MATCH"))

    # 6) needs-review pairs: one side is missing a critical attribute
    engine = TextVariationEngine(seed=rng.randint(0, 999999))
    count = 0
    shuffled_canonicals = canonicals[:]
    rng.shuffle(shuffled_canonicals)
    for cm in shuffled_canonicals:
        if count >= n_needs_review:
            break
        schema = CATEGORIES[cm["category"]]
        crit = schema["identity_critical"] + schema["variant_critical"]
        if not crit:
            continue
        drop_attr = rng.choice(crit)
        if drop_attr not in cm["attrs"]:
            continue
        desc, spec = engine.render(cm["category"], cm["attrs"], omit_attrs={drop_attr})
        r_missing = {"canonical_id": cm["canonical_id"], "category": cm["category"],
                     "cpse_name": rng.choice(CPSES), "cpse_material_code": "TEMP",
                     "description": desc, "specification": spec,
                     "attrs_json": json.dumps({k: v for k, v in cm["attrs"].items() if k != drop_attr}),
                     "omitted_attrs_json": json.dumps([drop_attr])}
        if cm["canonical_id"] not in by_canonical:
            continue
        r_full = by_canonical[cm["canonical_id"]].iloc[0].to_dict()
        pairs.append(_pair_row(r_missing, r_full, "NEEDS_REVIEW"))
        count += 1

    return pd.DataFrame(pairs)


def _pair_row(r1, r2, label):
    return {
        "canonical_id_a": r1["canonical_id"], "canonical_id_b": r2["canonical_id"],
        "category_a": r1["category"], "category_b": r2["category"],
        "cpse_a": r1["cpse_name"], "cpse_b": r2["cpse_name"],
        "description_a": r1["description"], "description_b": r2["description"],
        "specification_a": r1.get("specification", ""), "specification_b": r2.get("specification", ""),
        "attrs_json_a": r1["attrs_json"], "attrs_json_b": r2["attrs_json"],
        "label": label,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--variants-per-canonical", type=int, default=4)
    ap.add_argument("--max-per-category", type=int, default=250)
    ap.add_argument("--n-random-negatives", type=int, default=6000)
    ap.add_argument("--n-cross-category-negatives", type=int, default=2000)
    ap.add_argument("--n-needs-review", type=int, default=1500)
    ap.add_argument("--out-dir", default="../data/generated")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    engine = TextVariationEngine(seed=args.seed)

    print("Building canonical material world...")
    canonicals = build_canonical_materials(args.max_per_category, rng)
    print(f"  {len(canonicals)} canonical materials across {len(CATEGORIES)} categories")

    print("Manufacturing curated equivalence-rule twin canonicals...")
    eq_canonicals, twin_pairs = add_equivalence_canonicals(canonicals, rng)
    canonicals.extend(eq_canonicals)
    print(f"  +{len(eq_canonicals)} canonicals, {len(twin_pairs)} twin pairs "
          f"({len(EQUIVALENCE_RULES)} curated rules)")

    print("Rendering raw CPSE-style text records...")
    raw_df = render_raw_records(canonicals, args.variants_per_canonical, engine, rng)
    print(f"  {len(raw_df)} raw records")

    print("Finding attribute-neighbor canonical pairs (for VARIANT / NOT_A_MATCH hard cases)...")
    neighbor_pairs, cm_by_id = find_neighbors(canonicals, rng)
    print(f"  {len(neighbor_pairs)} neighbor relationships found")

    print("Building labeled pair dataset...")
    pairs_df = build_pairs(raw_df, canonicals, neighbor_pairs, twin_pairs, rng,
                            n_random_negatives=args.n_random_negatives,
                            n_cross_category_negatives=args.n_cross_category_negatives,
                            n_needs_review=args.n_needs_review)
    print(f"  {len(pairs_df)} labeled pairs")
    print(pairs_df["label"].value_counts())

    os.makedirs(args.out_dir, exist_ok=True)
    raw_path = os.path.join(args.out_dir, "raw_records.csv")
    pairs_path = os.path.join(args.out_dir, "pairs.csv")
    canon_path = os.path.join(args.out_dir, "canonical_materials.jsonl")
    raw_df.to_csv(raw_path, index=False)
    pairs_df.to_csv(pairs_path, index=False)
    with open(canon_path, "w") as f:
        for cm in canonicals:
            f.write(json.dumps(cm) + "\n")
    print(f"Wrote:\n  {raw_path}\n  {pairs_path}\n  {canon_path}")


if __name__ == "__main__":
    main()
