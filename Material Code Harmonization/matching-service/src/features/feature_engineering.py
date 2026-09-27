"""
Turns a pair of (description, specification, category) records into a numeric feature
vector for the matching classifier. Two feature families, matching the strategy doc's
hybrid design:

  1. Text similarity  -- lexical/character-level signal, useful for near-duplicate style
     detection (no pretrained transformer available in this environment -- see README's
     "Network / pretrained model handling" note -- so this uses TF-IDF (word + char
     n-gram) plus rapidfuzz string-distance ratios instead of sentence embeddings).

  2. Structured attribute comparison -- extracts category-aware attributes from both
     sides and explicitly flags identity-critical vs variant-critical disagreement. This
     is the signal that catches "textually near-identical but different material" cases
     that text similarity alone cannot (SS pipe vs CS pipe, TP304 vs TP316, etc.).
"""
import re
import numpy as np
from rapidfuzz import fuzz

from preprocessing.attribute_extraction import extract_attributes, CATEGORY_FIELDS
from data_generation.schemas import CATEGORIES

NUMERIC_FIELDS = {"size_mm", "bore_mm", "power_kw", "power_hp", "voltage_v", "rpm",
                   "cores", "csa_sqmm", "voltage_grade_kv", "micron_rating"}

FIELD_TO_SCHEMA_KEY = {
    "PIPE": {"material_type": "material", "size_mm": "nominal_size_mm", "grade": "grade",
              "schedule": "schedule", "standard": "standard"},
    "VALVE": {"valve_type": "valve_type", "material_type": "body_material",
               "size_mm": "nominal_size_mm", "pressure_class": "pressure_class", "standard": "standard"},
    "FLANGE": {"flange_type": "flange_type", "material_type": "material",
                "size_mm": "nominal_size_mm", "pressure_class": "pressure_class", "standard": "standard"},
    "GASKET": {"gasket_type": "gasket_type", "material_type": "material",
                "size_mm": "nominal_size_mm", "pressure_class": "pressure_class", "standard": "standard"},
    "BEARING": {"bearing_type": "bearing_type", "bearing_number": "bearing_number",
                 "bore_mm": "bore_mm", "seal_type": "seal_type"},
    "MOTOR": {"power_kw": "power_kw", "voltage_v": "voltage_v", "phase": "phase", "rpm": "rpm"},
    "CABLE": {"material_type": "conductor", "cores": "cores", "csa_sqmm": "csa_sqmm",
               "voltage_grade_kv": "voltage_grade_kv", "standard": "standard"},
    "PUMP": {"pump_type": "pump_type", "power_hp": "power_hp", "phase": "phase"},
    "FASTENER": {"fastener_type": "fastener_type", "bolt_size": "size", "grade": "grade"},
    "FILTER": {"filter_type": "filter_type", "micron_rating": "micron_rating",
                "size_mm": "connection_size_mm"},
}


def normalize_text(s: str) -> str:
    s = str(s) if s is not None and not (isinstance(s, float) and np.isnan(s)) else ""
    s = s.upper()
    s = re.sub(r"[^\w\s]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def _values_agree(a, b, numeric: bool) -> bool:
    if numeric:
        return abs(float(a) - float(b)) < 0.6
    return str(a).upper().replace(" ", "") == str(b).upper().replace(" ", "")


def attribute_comparison_features(cat_a: str, cat_b: str, desc_a: str, spec_a: str,
                                    desc_b: str, spec_b: str) -> dict:
    feats = {"same_category": 1.0 if cat_a == cat_b else 0.0,
              "n_attrs_compared": 0.0, "n_attrs_agree": 0.0, "n_attrs_conflict": 0.0,
              "frac_agree": 0.5, "identity_critical_conflict": 0.0,
              "variant_critical_conflict": 0.0, "any_type_conflict": 0.0,
              "identity_critical_missing": 0.0, "variant_critical_missing": 0.0,
              "n_critical_missing": 0.0}
    if cat_a != cat_b:
        return feats  # obviously different family -- nothing more to compare structurally

    schema = CATEGORIES.get(cat_a, {})
    field_map = FIELD_TO_SCHEMA_KEY.get(cat_a, {})
    attrs_a = extract_attributes(desc_a, spec_a, cat_a)
    attrs_b = extract_attributes(desc_b, spec_b, cat_a)

    compared = agree = conflict = 0
    identity_conflict = variant_conflict = 0
    identity_missing = variant_missing = n_critical_missing = 0
    for field in CATEGORY_FIELDS.get(cat_a, []):
        va, vb = attrs_a.get(field), attrs_b.get(field)
        schema_key = field_map.get(field)
        is_critical = schema_key in schema.get("identity_critical", []) or \
            schema_key in schema.get("variant_critical", [])
        if va is None or vb is None:
            # A missing critical attribute on either side is itself an important signal --
            # distinct from "compared and agreed". Without this the classifier can't tell
            # "material unstated -> needs review" apart from "material stated and matches".
            if is_critical and (va is None) != (vb is None):
                n_critical_missing += 1
                if schema_key in schema.get("identity_critical", []):
                    identity_missing = 1
                else:
                    variant_missing = 1
            continue
        compared += 1
        numeric = field in NUMERIC_FIELDS
        if _values_agree(va, vb, numeric):
            agree += 1
        else:
            conflict += 1
            if schema_key in schema.get("identity_critical", []):
                identity_conflict = 1
            elif schema_key in schema.get("variant_critical", []):
                variant_conflict = 1

    feats["n_attrs_compared"] = float(compared)
    feats["n_attrs_agree"] = float(agree)
    feats["n_attrs_conflict"] = float(conflict)
    feats["frac_agree"] = (agree / compared) if compared > 0 else 0.5
    feats["identity_critical_conflict"] = float(identity_conflict)
    feats["variant_critical_conflict"] = float(variant_conflict)
    feats["any_type_conflict"] = float(identity_conflict or variant_conflict)
    feats["identity_critical_missing"] = float(identity_missing)
    feats["variant_critical_missing"] = float(variant_missing)
    feats["n_critical_missing"] = float(n_critical_missing)
    return feats


def text_similarity_features(desc_a, spec_a, desc_b, spec_b, tfidf_word=None, tfidf_char=None) -> dict:
    ta = normalize_text(f"{desc_a} {spec_a}")
    tb = normalize_text(f"{desc_b} {spec_b}")
    feats = {
        "fuzz_ratio": fuzz.ratio(ta, tb) / 100.0,
        "fuzz_token_sort": fuzz.token_sort_ratio(ta, tb) / 100.0,
        "fuzz_partial": fuzz.partial_ratio(ta, tb) / 100.0,
        "len_ratio": (min(len(ta), len(tb)) / max(len(ta), len(tb))) if max(len(ta), len(tb)) > 0 else 1.0,
    }
    if tfidf_word is not None:
        va = tfidf_word.transform([ta])
        vb = tfidf_word.transform([tb])
        feats["tfidf_word_cosine"] = float((va @ vb.T).toarray()[0, 0])
    if tfidf_char is not None:
        va = tfidf_char.transform([ta])
        vb = tfidf_char.transform([tb])
        feats["tfidf_char_cosine"] = float((va @ vb.T).toarray()[0, 0])
    return feats


FEATURE_NAMES = [
    "same_category", "n_attrs_compared", "n_attrs_agree", "n_attrs_conflict", "frac_agree",
    "identity_critical_conflict", "variant_critical_conflict", "any_type_conflict",
    "identity_critical_missing", "variant_critical_missing", "n_critical_missing",
    "fuzz_ratio", "fuzz_token_sort", "fuzz_partial", "len_ratio",
    "tfidf_word_cosine", "tfidf_char_cosine",
]


def compute_pair_features(cat_a, cat_b, desc_a, spec_a, desc_b, spec_b,
                            tfidf_word=None, tfidf_char=None) -> dict:
    f = {}
    f.update(attribute_comparison_features(cat_a, cat_b, desc_a, spec_a, desc_b, spec_b))
    f.update(text_similarity_features(desc_a, spec_a, desc_b, spec_b, tfidf_word, tfidf_char))
    return f


def features_to_vector(f: dict) -> list:
    return [f.get(name, 0.0) for name in FEATURE_NAMES]
