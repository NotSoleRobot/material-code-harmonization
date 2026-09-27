"""
Clean inference API for the rest of the project (Spring Boot backend, or anything else)
to call. Loads the trained artifacts once, then exposes:

    compare_materials(record_a, record_b) -> structured result with explanation
    find_matches(material, candidate_pool) -> ranked candidates (category-blocked, not O(N^2))
    find_matches_batch(queries) -> batch ranked candidates
    generate_national_code(category, attrs) -> deterministic canonical code

WP3 / D4 — Confidence semantics:
    match_probability: sum of probabilities for {EXACT_DUPLICATE, NEAR_DUPLICATE, FUNCTIONALLY_EQUIVALENT}
    label_probability: probability of the top predicted class label
    confidence: alias for match_probability
"""
import hashlib
import json
import os

import joblib

from features.feature_engineering import compute_pair_features, features_to_vector, \
    CATEGORY_FIELDS, FIELD_TO_SCHEMA_KEY, NUMERIC_FIELDS
from preprocessing.attribute_extraction import extract_attributes
from data_generation.schemas import CATEGORIES

_MODELS_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "models")
_ARTIFACTS = None

HIGH_CONF_THRESHOLD = 0.85
MEDIUM_CONF_THRESHOLD = 0.60
DUPLICATE_CLASSES = {"EXACT_DUPLICATE", "NEAR_DUPLICATE", "FUNCTIONALLY_EQUIVALENT"}


class MaterialRecord(dict):
    """A material record is just a dict with at least 'description' and 'category';
    'specification' and 'cpse_material_code' are optional."""
    pass


def load_artifacts(models_dir: str = _MODELS_DIR):
    global _ARTIFACTS
    if _ARTIFACTS is not None:
        return _ARTIFACTS
    try:
        clf = joblib.load(os.path.join(models_dir, "classifier.joblib"))
        word_vec = joblib.load(os.path.join(models_dir, "tfidf_word.joblib"))
        char_vec = joblib.load(os.path.join(models_dir, "tfidf_char.joblib"))
        le = joblib.load(os.path.join(models_dir, "label_encoder.joblib"))
    except FileNotFoundError as e:
        raise RuntimeError(
            f"Model artifacts not found in {models_dir}. Run scripts/train.py first. ({e})"
        )
    _ARTIFACTS = {"clf": clf, "word_vec": word_vec, "char_vec": char_vec, "le": le}
    return _ARTIFACTS


def _safe_get(record: dict, key: str, default=""):
    v = record.get(key, default)
    return v if v is not None else default


def _confidence_tier(match_proba: float) -> str:
    if match_proba >= HIGH_CONF_THRESHOLD:
        return "HIGH"
    if match_proba >= MEDIUM_CONF_THRESHOLD:
        return "MEDIUM"
    return "LOW"


def _explain(cat_a, cat_b, desc_a, spec_a, desc_b, spec_b, predicted_label) -> dict:
    """Attribute-level explanation, not a bare score."""
    checks, warnings, conflicts = [], [], []
    if cat_a != cat_b:
        conflicts.append(f"Different material family: {cat_a} vs {cat_b}")
        return {"checks": checks, "warnings": warnings, "conflicts": conflicts}

    schema = CATEGORIES.get(cat_a, {})
    field_map = FIELD_TO_SCHEMA_KEY.get(cat_a, {})
    attrs_a = extract_attributes(desc_a, spec_a, cat_a)
    attrs_b = extract_attributes(desc_b, spec_b, cat_a)

    for field in CATEGORY_FIELDS.get(cat_a, []):
        va, vb = attrs_a.get(field), attrs_b.get(field)
        schema_key = field_map.get(field, field)
        label = schema_key.replace("_", " ")
        if va is None or vb is None:
            if schema_key in schema.get("identity_critical", []) + schema.get("variant_critical", []):
                warnings.append(f"{label.title()} not stated on one side -- cannot verify")
            continue
        numeric = field in NUMERIC_FIELDS
        agree = (abs(float(va) - float(vb)) < 0.6) if numeric else \
            (str(va).upper().replace(" ", "") == str(vb).upper().replace(" ", ""))
        if agree:
            checks.append(f"Same {label}: {va}")
        else:
            severity = "identity_critical" if schema_key in schema.get("identity_critical", []) else \
                ("variant_critical" if schema_key in schema.get("variant_critical", []) else "minor")
            conflicts.append(f"{label.title()} conflict ({severity}): {va} vs {vb}")

    return {"checks": checks, "warnings": warnings, "conflicts": conflicts}


def compare_materials(record_a: dict, record_b: dict) -> dict:
    """Core pairwise comparison API. Never raises on malformed/missing input."""
    try:
        artifacts = load_artifacts()
        cat_a = str(_safe_get(record_a, "category", "UNKNOWN")).upper()
        cat_b = str(_safe_get(record_b, "category", "UNKNOWN")).upper()
        desc_a = str(_safe_get(record_a, "description", ""))
        desc_b = str(_safe_get(record_b, "description", ""))
        spec_a = str(_safe_get(record_a, "specification", ""))
        spec_b = str(_safe_get(record_b, "specification", ""))

        if not desc_a.strip() or not desc_b.strip():
            return {
                "predicted_relationship": "NEEDS_REVIEW",
                "label_probability": 0.0,
                "match_probability": 0.0,
                "confidence": 0.0,
                "confidence_tier": "LOW",
                "explanation": {"checks": [], "warnings": ["One or both descriptions are empty"], "conflicts": []},
                "note": "Cannot compare: missing description text.",
            }

        f = compute_pair_features(cat_a, cat_b, desc_a, spec_a, desc_b, spec_b,
                                    artifacts["word_vec"], artifacts["char_vec"])
        X = [features_to_vector(f)]
        proba = artifacts["clf"].predict_proba(X)[0]
        pred_idx = proba.argmax()
        predicted_label = artifacts["le"].inverse_transform([pred_idx])[0]

        # WP3 / D4: Separate match probability from label probability
        match_probability = sum(
            p for cls, p in zip(artifacts["le"].classes_, proba) if cls in DUPLICATE_CLASSES
        )
        label_probability = float(proba[pred_idx])

        class_probabilities = {
            cls: float(p) for cls, p in zip(artifacts["le"].classes_, proba)
        }

        explanation = _explain(cat_a, cat_b, desc_a, spec_a, desc_b, spec_b, predicted_label)

        return {
            "predicted_relationship": predicted_label,
            "label_probability": round(label_probability, 4),
            "match_probability": round(float(match_probability), 4),
            "confidence": round(float(match_probability), 4),  # alias for backward compatibility
            "confidence_tier": _confidence_tier(match_probability),
            "class_probabilities": {k: round(v, 4) for k, v in class_probabilities.items()},
            "explanation": explanation,
            "features": f,
        }
    except Exception as e:
        return {
            "predicted_relationship": "NEEDS_REVIEW",
            "label_probability": 0.0,
            "match_probability": 0.0,
            "confidence": 0.0,
            "confidence_tier": "LOW",
            "explanation": {"checks": [], "warnings": [f"Comparison failed: {e}"], "conflicts": []},
            "note": "Unexpected input caused a comparison error; routed to human review.",
        }


def find_matches(material: dict, candidate_pool: list, top_k: int = 5,
                 prefilter_k: int = 25) -> list:
    """Two-stage retrieval with noise filtering."""
    artifacts = load_artifacts()
    cat = str(_safe_get(material, "category", "UNKNOWN")).upper()
    blocked = [c for c in candidate_pool if str(_safe_get(c, "category", "")).upper() == cat]
    if not blocked:
        return []

    query_text = normalize_text_for_prefilter(material)
    cand_texts = [normalize_text_for_prefilter(c) for c in blocked]
    word_vec = artifacts["word_vec"]
    qv = word_vec.transform([query_text])
    cv = word_vec.transform(cand_texts)
    sims = (cv @ qv.T).toarray().ravel()
    top_idx = sims.argsort()[::-1][:prefilter_k]
    shortlisted = [blocked[i] for i in top_idx]

    results = []
    for cand in shortlisted:
        r = compare_materials(material, cand)
        # WP3 Task 3 / M-06: drop candidates whose match_probability < 0.30
        if r.get("match_probability", 0.0) >= 0.30:
            r["candidate"] = cand
            results.append(r)

    results.sort(key=lambda r: r.get("match_probability", 0.0), reverse=True)
    return results[:top_k]


def find_matches_batch(queries: list) -> list:
    """WP3 Task 2: Batch candidate retrieval for multiple query materials."""
    batch_results = []
    for q in queries:
        material = q.get("material")
        candidates = q.get("candidates", [])
        top_k = int(q.get("top_k", 5))
        if material is None:
            batch_results.append({"matches": []})
        else:
            matches = find_matches(material, candidates, top_k=top_k)
            batch_results.append({"matches": matches})
    return batch_results


def normalize_text_for_prefilter(record: dict) -> str:
    from features.feature_engineering import normalize_text
    return normalize_text(f'{_safe_get(record, "description", "")} {_safe_get(record, "specification", "")}')


def generate_national_code(category: str, attrs: dict) -> str:
    """Propose a deterministic code based on category and critical attributes."""
    schema = CATEGORIES.get(category.upper(), {})
    critical_keys = sorted(set(schema.get("identity_critical", []) + schema.get("variant_critical", [])))
    signature = json.dumps({k: attrs.get(k) for k in critical_keys}, sort_keys=True)
    digest = hashlib.sha256(signature.encode()).hexdigest()[:8].upper()
    return f"{category.upper()[:4]}-{digest}"
