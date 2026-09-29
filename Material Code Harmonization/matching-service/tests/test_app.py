import unittest
import json
import os
import sys
import csv
from unittest.mock import patch

# Ensure src/ is on the path for local runs (Docker sets PYTHONPATH=/app/src)
_SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)
# Also add the service root so 'app' is importable
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app import app


class TestMatchingServiceApp(unittest.TestCase):
    def setUp(self):
        self.app = app.test_client()
        self.app.testing = True

    def test_health(self):
        response = self.app.get("/health")
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertEqual(data.get("status"), "ok")
        self.assertEqual(data.get("service"), "numm-matching-service")

    def test_hosted_service_token_protects_matching_routes(self):
        with patch.dict(os.environ, {"MATCHING_SERVICE_TOKEN": "hosted-secret"}):
            health = self.app.get("/health")
            denied = self.app.get("/schema/PIPE")
            allowed = self.app.get(
                "/schema/PIPE",
                headers={"X-Service-Token": "hosted-secret"},
            )

        self.assertEqual(health.status_code, 200)
        self.assertEqual(denied.status_code, 401)
        self.assertEqual(allowed.status_code, 200)

    def test_compare_pipes(self):
        payload = {
            "material_a": {
                "description": "CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB",
                "category": "PIPE",
                "specification": "ASTM A106 GR.B"
            },
            "material_b": {
                "description": "CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239",
                "category": "PIPE",
                "specification": "IS 1239"
            }
        }
        response = self.app.post("/compare",
                                 data=json.dumps(payload),
                                 content_type="application/json")
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertIn("predicted_relationship", data)
        self.assertIn("confidence", data)
        self.assertIn("confidence_tier", data)
        self.assertIn("explanation", data)
        self.assertIn("checks", data["explanation"])

    def test_compare_different_categories(self):
        payload = {
            "material_a": {
                "description": "BALL BEARING 6205 2RS SKF",
                "category": "BEARING"
            },
            "material_b": {
                "description": "GATE VALVE 2 INCH 150# RF FLANGED",
                "category": "VALVE"
            }
        }
        response = self.app.post("/compare",
                                 data=json.dumps(payload),
                                 content_type="application/json")
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertEqual(data["predicted_relationship"], "NOT_A_MATCH")
        self.assertLess(data["match_probability"], 0.30)
        self.assertGreater(data["label_probability"], data["match_probability"])
        self.assertEqual(data["confidence_tier"], "LOW")

    def test_extract_attributes_endpoint(self):
        response = self.app.post("/extract-attributes", json={"materials": [{
            "material_id": 1,
            "description": "CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB",
            "specification": "ASTM A106 GR.B",
            "category": "PIPE",
        }]})
        self.assertEqual(response.status_code, 200)
        result = response.get_json()["results"][0]
        self.assertEqual(result["material_id"], 1)
        self.assertEqual(result["attributes"]["material"], "CS")
        self.assertTrue(result["identity_critical_present"])
        self.assertEqual(result["missing_identity_keys"], [])

    def test_open_domain_schema_and_attribute_extraction(self):
        schema_response = self.app.get("/schema/INSTRUMENTATION")
        self.assertEqual(schema_response.status_code, 200)
        schema = schema_response.get_json()
        self.assertEqual(schema["identity_critical"], ["material"])
        self.assertIn("model", schema["all_fields"])

        extraction = self.app.post("/extract-attributes", json={"materials": [{
            "material_id": 77,
            "description": "SS316 PRESSURE TRANSMITTER PTX-500 50MM",
            "specification": "IEC 61508",
            "category": "INSTRUMENTATION",
        }]})
        self.assertEqual(extraction.status_code, 200)
        attributes = extraction.get_json()["results"][0]["attributes"]
        self.assertEqual(attributes["material"], "SS316")
        self.assertEqual(attributes["nominal_size_mm"], 50.0)
        self.assertEqual(attributes["model"], "PTX-500")
        self.assertEqual(attributes["standard"], "IEC61508")

    def test_open_domain_comparison_uses_text_fallback(self):
        response = self.app.post("/compare", json={
            "material_a": {"description": "SS316 PRESSURE TRANSMITTER PTX-500 50MM", "category": "INSTRUMENTATION"},
            "material_b": {"description": "PRESSURE TRANSMITTER PTX-500 SS316 50 MM", "category": "INSTRUMENTATION"},
        })
        self.assertEqual(response.status_code, 200)
        result = response.get_json()
        self.assertIn(result["predicted_relationship"], {
            "EXACT_DUPLICATE", "NEAR_DUPLICATE", "FUNCTIONALLY_EQUIVALENT", "NEEDS_REVIEW"})
        self.assertIn("Open-domain weighted text comparison used", result["explanation"]["warnings"])

    def test_near_miss_different_size_is_not_a_duplicate(self):
        response = self.app.post("/compare", json={
            "material_a": {"description": "CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB", "category": "PIPE"},
            "material_b": {"description": "CS SEAMLESS PIPE 200MM SCH40 ASTM A106 GRB", "category": "PIPE"},
        })
        data = response.get_json()
        self.assertEqual(response.status_code, 200)
        self.assertNotIn(data["predicted_relationship"], {"EXACT_DUPLICATE", "NEAR_DUPLICATE"})
        self.assertLess(data["match_probability"], 0.60)

    def test_malformed_input_never_500s(self):
        cases = [
            ("/compare", None),
            ("/compare", {"material_a": {}}),
            ("/find-matches", {"material": {}, "candidates": "wrong"}),
            ("/find-matches", {"material": {}, "candidates": [], "top_k": "absurd"}),
            ("/find-matches-batch", {"queries": [{"material": {}, "candidates": "wrong"}]}),
            ("/extract-attributes", {"materials": "wrong"}),
        ]
        for endpoint, payload in cases:
            response = self.app.post(endpoint, json=payload)
            self.assertNotEqual(response.status_code, 500, endpoint)

    def test_true_match_pairs_accepted(self):
        pairs_path = os.path.join(_ROOT, "data", "generated", "pairs.csv")
        regression_indexes = {0, 1, 2, 3, 4, 5, 7, 8, 9, 11, 12, 13, 14, 15,
                              16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29}
        checked = 0
        with open(pairs_path, newline="", encoding="utf-8") as source:
            for index, row in enumerate(csv.DictReader(source)):
                if index not in regression_indexes:
                    continue
                response = self.app.post("/compare", json={
                    "material_a": {"description": row["description_a"], "specification": row["specification_a"], "category": row["category_a"]},
                    "material_b": {"description": row["description_b"], "specification": row["specification_b"], "category": row["category_b"]},
                })
                self.assertEqual(response.status_code, 200)
                self.assertGreaterEqual(response.get_json()["match_probability"], 0.30)
                checked += 1
        self.assertEqual(checked, 28)

    def test_find_matches_batch(self):
        query = {
            "material": {"description": "GATE VALVE CS CLASS150 2INCH API600", "category": "VALVE"},
            "candidates": [{"description": "GATE VALVE 2 INCH 150# RF FLANGED CS", "category": "VALVE"}],
            "top_k": 5,
        }
        response = self.app.post("/find-matches-batch", json={"queries": [query, query]})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.get_json()["results"]), 2)

    def test_find_matches(self):
        payload = {
            "material": {
                "description": "GATE VALVE CS CLASS150 2INCH API600",
                "category": "VALVE"
            },
            "candidates": [
                {
                    "description": "GATE VALVE 2 INCH 150# RF FLANGED CS",
                    "category": "VALVE",
                    "cpse_material_code": "IOCL-VLV-01"
                },
                {
                    "description": "GLOBE VALVE 4 INCH 300# FLANGED",
                    "category": "VALVE",
                    "cpse_material_code": "ONGC-VLV-99"
                }
            ],
            "top_k": 2
        }
        response = self.app.post("/find-matches",
                                 data=json.dumps(payload),
                                 content_type="application/json")
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertIn("matches", data)
        self.assertGreater(len(data["matches"]), 0)

if __name__ == "__main__":
    unittest.main()
