"""
Flask HTTP server — exposes the NUMM matching engine over HTTP so the
Spring Boot backend can call it via REST.

Endpoints
---------
GET  /health                  — liveness probe
POST /compare                 — pairwise material comparison
POST /find-matches            — ranked candidate retrieval for one material

All responses are JSON. All errors degrade gracefully — no endpoint
raises an unhandled 500; malformed input returns a 400 with a clear message.

Run (development):
    PYTHONPATH=src python app.py

Run (production — recommended for Docker):
    gunicorn -w 2 -b 0.0.0.0:5000 app:app
"""

import os
import secrets

from flask import Flask, request, jsonify
from matching.inference import compare_materials, find_matches, find_matches_batch
from preprocessing.attribute_extraction import extract_attributes
from data_generation.schemas import CATEGORIES
from features.feature_engineering import FIELD_TO_SCHEMA_KEY

app = Flask(__name__)


@app.before_request
def require_service_token():
    """Protect hosted inference endpoints while keeping local Docker unchanged."""
    if request.path == "/health":
        return None
    expected_token = os.getenv("MATCHING_SERVICE_TOKEN", "").strip()
    if not expected_token:
        return None
    supplied_token = request.headers.get("X-Service-Token", "")
    if not secrets.compare_digest(supplied_token, expected_token):
        return jsonify({"error": "Unauthorized matching-service request"}), 401
    return None


def _top_k(value):
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("'top_k' must be an integer") from exc
    if parsed < 1 or parsed > 50:
        raise ValueError("'top_k' must be between 1 and 50")
    return parsed


@app.errorhandler(ValueError)
def bad_input(error):
    return jsonify({"error": str(error)}), 400


@app.errorhandler(Exception)
def service_failure(error):
    app.logger.exception("Matching service request failed")
    return jsonify({"error": "Matching service failure", "message": str(error)}), 503


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------

@app.route("/health", methods=["GET"])
def health():
    """Spring Boot actuator or Docker HEALTHCHECK can poll this."""
    return jsonify({"status": "ok", "service": "numm-matching-service"}), 200


# ---------------------------------------------------------------------------
# GET /schema/<category>   (WP1 Task 2)
# ---------------------------------------------------------------------------

@app.route("/schema/<category>", methods=["GET"])
def schema(category: str):
    """
    Return the identity_critical, variant_critical, and all_fields for a category.

    Response (JSON):
    {
        "category": "PIPE",
        "identity_critical": ["material"],
        "variant_critical": ["nominal_size_mm", "schedule", "grade"],
        "all_fields": ["material", "grade", "nominal_size_mm", "schedule"]
    }
    """
    cat = category.upper()
    schema_data = CATEGORIES.get(cat)
    if schema_data is None:
        return jsonify({
            "category": cat,
            "identity_critical": ["material"],
            "variant_critical": ["nominal_size_mm", "standard"],
            "all_fields": ["material", "grade", "nominal_size_mm", "standard", "model"],
        }), 200
    return jsonify({
        "category": cat,
        "identity_critical": schema_data.get("identity_critical", []),
        "variant_critical": schema_data.get("variant_critical", []),
        "all_fields": list(schema_data.get("attrs", {}).keys()),
    }), 200


# ---------------------------------------------------------------------------
# POST /extract-attributes  (WP1 Task 1)
# ---------------------------------------------------------------------------

@app.route("/extract-attributes", methods=["POST"])
def extract_attributes_endpoint():
    """
    Batch attribute extraction.  Accepts a list of materials and returns
    extracted structured attributes plus identity-completeness flags.

    Request body (JSON):
    {
        "materials": [
            {
                "material_id": 1,
                "description": "CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB",
                "specification": "ASTM A106 GR.B",
                "category": "PIPE"
            },
            ...
        ]
    }

    Response (JSON):
    {
        "results": [
            {
                "material_id": 1,
                "category": "PIPE",
                "attributes": {"material": "CS", "nominal_size_mm": 50.0, "schedule": "SCH40"},
                "identity_critical_present": true,
                "missing_identity_keys": []
            },
            ...
        ]
    }
    """
    body = request.get_json(silent=True)
    if not body:
        return jsonify({"error": "Request body must be JSON"}), 400

    materials = body.get("materials")
    if not isinstance(materials, list):
        return jsonify({"error": "'materials' must be a list"}), 400

    results = []
    for item in materials:
        if not isinstance(item, dict):
            continue
        mat_id = item.get("material_id")
        description = item.get("description", "")
        specification = item.get("specification", "")
        category = str(item.get("category", "UNKNOWN")).upper()

        try:
            raw_attrs = extract_attributes(description, specification, category)
            field_map = FIELD_TO_SCHEMA_KEY.get(category, {})
            attrs = {field_map.get(key, key): value for key, value in raw_attrs.items()}
        except Exception as e:
            attrs = {}

        # Check identity-critical completeness
        schema_data = CATEGORIES.get(category, {
            "identity_critical": ["material"],
            "variant_critical": ["nominal_size_mm", "standard"],
        })
        identity_keys = schema_data.get("identity_critical", [])
        missing = [k for k in identity_keys if not attrs.get(k)]
        identity_complete = len(missing) == 0 and len(identity_keys) > 0

        results.append({
            "material_id": mat_id,
            "category": category,
            "attributes": attrs,
            "identity_critical_present": identity_complete,
            "missing_identity_keys": missing,
        })

    return jsonify({"results": results}), 200


# ---------------------------------------------------------------------------
# POST /compare
# ---------------------------------------------------------------------------

@app.route("/compare", methods=["POST"])
def compare():
    """
    Compare two material records and return a relationship label + confidence.

    Request body (JSON):
    {
        "material_a": {
            "description": "CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB",
            "category":    "PIPE",
            "specification": "..."          // optional
        },
        "material_b": {
            "description": "CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239",
            "category":    "PIPE",
            "specification": "..."          // optional
        }
    }

    Response (JSON):
    {
        "predicted_relationship": "NEAR_DUPLICATE",
        "confidence": 0.9123,
        "confidence_tier": "HIGH",
        "class_probabilities": { ... },
        "explanation": {
            "checks":    ["Same material_type: CS", "Same size_mm: 50.0"],
            "warnings":  [],
            "conflicts": []
        },
        "features": { ... }
    }
    """
    body = request.get_json(silent=True)
    if not body:
        return jsonify({"error": "Request body must be JSON"}), 400

    material_a = body.get("material_a")
    material_b = body.get("material_b")
    if material_a is None or material_b is None:
        return jsonify({"error": "Both 'material_a' and 'material_b' are required"}), 400

    result = compare_materials(material_a, material_b)
    return jsonify(result), 200


# ---------------------------------------------------------------------------
# POST /find-matches
# ---------------------------------------------------------------------------

@app.route("/find-matches", methods=["POST"])
def find_matches_endpoint():
    """
    Find the best matches for a query material from a candidate pool.
    Uses two-stage retrieval: category blocking + TF-IDF prefilter → full scoring.

    Request body (JSON):
    {
        "material": {
            "description": "GATE VALVE CS CLASS150 2INCH API600",
            "category":    "VALVE"
        },
        "candidates": [
            { "description": "...", "category": "VALVE", "cpse_material_code": "GV-001" },
            ...
        ],
        "top_k": 5         // optional, default 5
    }

    Response (JSON):
    {
        "matches": [
            {
                "predicted_relationship": "NEAR_DUPLICATE",
                "confidence": 0.94,
                "confidence_tier": "HIGH",
                "explanation": { ... },
                "candidate": { "description": "...", "cpse_material_code": "GV-001" }
            },
            ...
        ]
    }
    """
    body = request.get_json(silent=True)
    if not body:
        return jsonify({"error": "Request body must be JSON"}), 400

    material = body.get("material")
    candidates = body.get("candidates")
    if material is None:
        return jsonify({"error": "'material' is required"}), 400
    if candidates is None or not isinstance(candidates, list):
        return jsonify({"error": "'candidates' must be a list"}), 400

    if not isinstance(material, dict):
        return jsonify({"error": "'material' must be an object"}), 400
    if any(not isinstance(candidate, dict) for candidate in candidates):
        return jsonify({"error": "Every candidate must be an object"}), 400
    top_k = _top_k(body.get("top_k", 5))
    results = find_matches(material, candidates, top_k=top_k)
    return jsonify({"matches": results}), 200


# ---------------------------------------------------------------------------
# POST /find-matches-batch  (WP3 Task 2)
# ---------------------------------------------------------------------------

@app.route("/find-matches-batch", methods=["POST"])
def find_matches_batch_endpoint():
    """
    Batch find-matches endpoint for multiple query materials.
    Request body (JSON):
    {
        "queries": [
            {
                "material": { "description": "...", "category": "PIPE" },
                "candidates": [ ... ],
                "top_k": 5
            },
            ...
        ]
    }
    Response (JSON):
    {
        "results": [
            { "matches": [ ... ] },
            ...
        ]
    }
    """
    body = request.get_json(silent=True)
    if not body:
        return jsonify({"error": "Request body must be JSON"}), 400

    queries = body.get("queries")
    if not isinstance(queries, list):
        return jsonify({"error": "'queries' must be a list"}), 400

    for query in queries:
        if not isinstance(query, dict) or not isinstance(query.get("material"), dict):
            return jsonify({"error": "Every query must contain a material object"}), 400
        if not isinstance(query.get("candidates", []), list):
            return jsonify({"error": "Every query candidates value must be a list"}), 400
        query["top_k"] = _top_k(query.get("top_k", 5))

    results = find_matches_batch(queries)
    return jsonify({"results": results}), 200


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    # Debug=False in any real run; Spring Boot will call this over the network
    port = int(os.environ.get("PORT", 5000))
    print(f"Starting NUMM matching service on port {port} ...")
    app.run(host="0.0.0.0", port=port, debug=False)
