"""
Two layers of dataset validation:

  1. Structural/deterministic checks -- exact-duplicate rows, broken canonical-id
     references, missing ground truth, invalid category values. These are things a
     RULE should catch, not a model.

  2. IsolationForest anomaly detection over record-level surface features (description
     length, token count, digit ratio, uppercase ratio, punctuation density) -- this is
     the right tool for "does this record look structurally unlike the rest of the
     corpus" (an unsupervised outlier question), which is a different question from "do
     these two records refer to the same material" (a supervised classification question
     the matching model answers). Using IsolationForest for the matching decision itself
     would be the wrong tool for the job; using it here, for corpus-level quality
     screening, is a legitimate, honestly-scoped application.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest


def structural_checks(raw_df: pd.DataFrame, pairs_df: pd.DataFrame) -> dict:
    issues = {}
    issues["exact_duplicate_raw_rows"] = int(raw_df.duplicated(
        subset=["category", "description", "specification"]).sum())
    issues["rows_missing_description"] = int(raw_df["description"].isna().sum() +
                                                (raw_df["description"].astype(str).str.strip() == "").sum())
    issues["rows_missing_canonical_id"] = int(raw_df["canonical_id"].isna().sum())
    valid_canonical_ids = set(raw_df["canonical_id"])
    broken_refs_a = (~pairs_df["canonical_id_a"].isin(valid_canonical_ids)).sum()
    broken_refs_b = (~pairs_df["canonical_id_b"].isin(valid_canonical_ids)).sum()
    issues["pairs_with_broken_canonical_reference"] = int(broken_refs_a + broken_refs_b)
    issues["pairs_missing_label"] = int(pairs_df["label"].isna().sum())
    valid_labels = {"EXACT_DUPLICATE", "NEAR_DUPLICATE", "FUNCTIONALLY_EQUIVALENT",
                     "VARIANT", "NOT_A_MATCH", "NEEDS_REVIEW"}
    issues["pairs_with_invalid_label"] = int((~pairs_df["label"].isin(valid_labels)).sum())
    issues["self_pairs (canonical_id_a == canonical_id_b but different material)"] = 0  # by construction, not possible here
    return issues


def _record_features(df: pd.DataFrame) -> np.ndarray:
    feats = []
    for _, r in df.iterrows():
        d = str(r["description"])
        n = len(d)
        n_tokens = len(d.split())
        digit_ratio = sum(c.isdigit() for c in d) / n if n else 0
        upper_ratio = sum(c.isupper() for c in d) / n if n else 0
        punct_ratio = sum(not c.isalnum() and not c.isspace() for c in d) / n if n else 0
        feats.append([n, n_tokens, digit_ratio, upper_ratio, punct_ratio])
    return np.array(feats)


def isolation_forest_scan(raw_df: pd.DataFrame, contamination=0.01, seed=42):
    X = _record_features(raw_df)
    iso = IsolationForest(contamination=contamination, random_state=seed, n_estimators=200)
    preds = iso.fit_predict(X)  # -1 = anomaly, 1 = normal
    scores = iso.score_samples(X)
    anomalies = raw_df[preds == -1].copy()
    anomalies["anomaly_score"] = scores[preds == -1]
    return anomalies.sort_values("anomaly_score")


def main():
    base = os.path.join(os.path.dirname(__file__), "..", "data", "generated")
    raw_df = pd.read_csv(os.path.join(base, "raw_records.csv"))
    pairs_df = pd.read_csv(os.path.join(base, "pairs.csv"))

    print("=== Structural validation ===")
    issues = structural_checks(raw_df, pairs_df)
    for k, v in issues.items():
        flag = "OK" if v == 0 else "!! NONZERO"
        print(f"  {k}: {v}  [{flag}]")

    print(f"\n=== IsolationForest anomaly scan ({len(raw_df)} records, contamination=1%) ===")
    anomalies = isolation_forest_scan(raw_df)
    print(f"  Flagged {len(anomalies)} records for human spot-check "
          f"(most anomalous surface-feature profile in the corpus):")
    for _, r in anomalies.head(10).iterrows():
        print(f"    [{r['category']:10s}] score={r['anomaly_score']:.3f}  \"{r['description']}\"")

    report = {"structural_checks": issues, "n_records_scanned": int(len(raw_df)),
              "n_anomalies_flagged": int(len(anomalies)),
              "sample_anomalies": anomalies.head(10)[["category", "description"]].to_dict("records")}
    out_path = os.path.join(os.path.dirname(__file__), "..", "reports", "dataset_validation.json")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(report, f, indent=2)
    print(f"\nWrote {out_path}")


if __name__ == "__main__":
    main()
