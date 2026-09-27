"""
Trains the NUMM matching classifier and a naive baseline for comparison, with a
canonical-material-group split to prevent train/test leakage (a pair only lands in a
split if BOTH of its canonical materials were assigned to that split -- pairs that
straddle two splits are dropped rather than risking leakage).
"""
import argparse
import json
import os

import numpy as np
import pandas as pd
import joblib
from sklearn.ensemble import RandomForestClassifier
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.preprocessing import LabelEncoder
from sklearn.metrics import (classification_report, confusion_matrix, f1_score,
                              precision_score, recall_score)

from features.feature_engineering import compute_pair_features, features_to_vector, \
    normalize_text, FEATURE_NAMES

LABELS = ["EXACT_DUPLICATE", "NEAR_DUPLICATE", "FUNCTIONALLY_EQUIVALENT",
          "VARIANT", "NOT_A_MATCH", "NEEDS_REVIEW"]


def canonical_group_split(pairs_df: pd.DataFrame, seed=42, train_frac=0.70, val_frac=0.15):
    all_ids = sorted(set(pairs_df["canonical_id_a"]) | set(pairs_df["canonical_id_b"]))
    rng = np.random.RandomState(seed)
    perm = rng.permutation(all_ids)
    n = len(perm)
    n_train = int(n * train_frac)
    n_val = int(n * val_frac)
    train_ids = set(perm[:n_train])
    val_ids = set(perm[n_train:n_train + n_val])
    test_ids = set(perm[n_train + n_val:])

    def split_of(row):
        a, b = row["canonical_id_a"], row["canonical_id_b"]
        if a in train_ids and b in train_ids:
            return "train"
        if a in val_ids and b in val_ids:
            return "val"
        if a in test_ids and b in test_ids:
            return "test"
        return "cross"  # straddles two splits -- dropped, not assigned anywhere

    pairs_df = pairs_df.copy()
    pairs_df["split"] = pairs_df.apply(split_of, axis=1)
    return pairs_df, train_ids, val_ids, test_ids


def fit_tfidf(train_texts):
    word_vec = TfidfVectorizer(analyzer="word", ngram_range=(1, 2), min_df=2)
    char_vec = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), min_df=2)
    word_vec.fit(train_texts)
    char_vec.fit(train_texts)
    return word_vec, char_vec


def build_feature_matrix(df: pd.DataFrame, word_vec, char_vec):
    rows = []
    for _, r in df.iterrows():
        f = compute_pair_features(r["category_a"], r["category_b"],
                                    r["description_a"], r["specification_a"],
                                    r["description_b"], r["specification_b"],
                                    word_vec, char_vec)
        rows.append(features_to_vector(f))
    return np.array(rows)


def baseline_predict(df: pd.DataFrame, word_vec, threshold: float):
    """Naive baseline: TF-IDF word-cosine similarity + a single fixed threshold, exactly
    the 'obvious solution' the strategy doc identifies as insufficient. No attributes."""
    preds = []
    for _, r in df.iterrows():
        ta = normalize_text(f'{r["description_a"]} {r["specification_a"]}')
        tb = normalize_text(f'{r["description_b"]} {r["specification_b"]}')
        va, vb = word_vec.transform([ta]), word_vec.transform([tb])
        sim = float((va @ vb.T).toarray()[0, 0])
        preds.append(sim >= threshold)
    return np.array(preds)


def is_same_material(labels):
    return np.isin(labels, ["EXACT_DUPLICATE", "NEAR_DUPLICATE"])


def find_best_threshold(sims, y_true_binary):
    best_t, best_f1 = 0.5, -1
    for t in np.arange(0.30, 0.96, 0.01):
        pred = sims >= t
        f1 = f1_score(y_true_binary, pred, zero_division=0)
        if f1 > best_f1:
            best_f1, best_t = f1, t
    return best_t, best_f1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", default="../../data/generated/pairs.csv")
    ap.add_argument("--out-dir", default="../../models")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    print("Loading pairs...")
    pairs = pd.read_csv(args.pairs)
    pairs, train_ids, val_ids, test_ids = canonical_group_split(pairs, seed=args.seed)
    print(pairs["split"].value_counts())

    train_df = pairs[pairs["split"] == "train"].reset_index(drop=True)
    val_df = pairs[pairs["split"] == "val"].reset_index(drop=True)
    test_df = pairs[pairs["split"] == "test"].reset_index(drop=True)

    print(f"\nSplit sizes -> train={len(train_df)} val={len(val_df)} test={len(test_df)}")
    print("Train label distribution:\n", train_df["label"].value_counts())

    # sanity check: no canonical id appears in more than one split
    overlap_tv = train_ids & val_ids
    overlap_tt = train_ids & test_ids
    overlap_vt = val_ids & test_ids
    assert not overlap_tv and not overlap_tt and not overlap_vt, "LEAKAGE: canonical id in >1 split"
    print("Leakage check passed: train/val/test canonical-id sets are disjoint.")

    print("\nFitting TF-IDF vectorizers on TRAIN text only...")
    train_texts = list(pd.concat([
        train_df["description_a"] + " " + train_df["specification_a"].fillna(""),
        train_df["description_b"] + " " + train_df["specification_b"].fillna(""),
    ]).map(normalize_text))
    word_vec, char_vec = fit_tfidf(train_texts)
    print(f"  word vocab size: {len(word_vec.vocabulary_)}  char vocab size: {len(char_vec.vocabulary_)}")

    print("\nBuilding feature matrices (this calls the attribute extractor + TF-IDF for every pair)...")
    X_train = build_feature_matrix(train_df, word_vec, char_vec)
    X_val = build_feature_matrix(val_df, word_vec, char_vec)
    X_test = build_feature_matrix(test_df, word_vec, char_vec)
    print(f"  X_train {X_train.shape}  X_val {X_val.shape}  X_test {X_test.shape}")

    le = LabelEncoder().fit(LABELS)
    y_train = le.transform(train_df["label"])
    y_val = le.transform(val_df["label"])
    y_test = le.transform(test_df["label"])

    print("\n=== BASELINE: TF-IDF cosine + fixed threshold (binary same-material only) ===")

    def pair_text_sim(row, vec):
        ta = normalize_text(f'{row["description_a"]} {row["specification_a"]}')
        tb = normalize_text(f'{row["description_b"]} {row["specification_b"]}')
        va, vb = vec.transform([ta]), vec.transform([tb])
        return float((va @ vb.T).toarray()[0, 0])

    sims_val = np.array([pair_text_sim(r, word_vec) for _, r in val_df.iterrows()])
    y_val_binary = is_same_material(val_df["label"].values)
    best_t, best_val_f1 = find_best_threshold(sims_val, y_val_binary)
    print(f"  Threshold selected on VAL set: {best_t:.2f} (val F1={best_val_f1:.3f})")

    baseline_pred_test = baseline_predict(test_df, word_vec, best_t)
    y_test_binary = is_same_material(test_df["label"].values)
    baseline_precision = precision_score(y_test_binary, baseline_pred_test, zero_division=0)
    baseline_recall = recall_score(y_test_binary, baseline_pred_test, zero_division=0)
    baseline_f1 = f1_score(y_test_binary, baseline_pred_test, zero_division=0)
    print(f"  TEST (binary same-material): precision={baseline_precision:.3f} "
          f"recall={baseline_recall:.3f} f1={baseline_f1:.3f}")

    # baseline hard-negative check: VARIANT + NOT_A_MATCH pairs the baseline still merges
    hard_mask = np.isin(test_df["label"].values, ["VARIANT", "NOT_A_MATCH"])
    baseline_false_merge_rate = baseline_pred_test[hard_mask].mean() if hard_mask.sum() else float("nan")
    print(f"  Baseline false-merge rate on hard negatives (VARIANT/NOT_A_MATCH, should be 0): "
          f"{baseline_false_merge_rate:.3f}  (n={hard_mask.sum()})")

    print("\n=== FINAL MODEL: hybrid (TF-IDF + fuzzy text + structured attributes) RandomForest ===")
    clf = RandomForestClassifier(n_estimators=300, max_depth=18, class_weight="balanced",
                                   random_state=args.seed, n_jobs=-1)
    clf.fit(X_train, y_train)

    val_pred = clf.predict(X_val)
    print("VAL macro F1:", f1_score(y_val, val_pred, average="macro"))

    test_pred = clf.predict(X_test)
    test_pred_labels = le.inverse_transform(test_pred)
    test_true_labels = le.inverse_transform(y_test)

    print("\nTEST classification report (multiclass, 6-way):")
    report = classification_report(test_true_labels, test_pred_labels, labels=LABELS, zero_division=0)
    print(report)

    cm = confusion_matrix(test_true_labels, test_pred_labels, labels=LABELS)
    print("Confusion matrix (rows=true, cols=pred), label order:", LABELS)
    print(cm)

    hybrid_pred_binary = is_same_material(test_pred_labels)
    hybrid_precision = precision_score(y_test_binary, hybrid_pred_binary, zero_division=0)
    hybrid_recall = recall_score(y_test_binary, hybrid_pred_binary, zero_division=0)
    hybrid_f1 = f1_score(y_test_binary, hybrid_pred_binary, zero_division=0)
    hybrid_false_merge_rate = hybrid_pred_binary[hard_mask].mean() if hard_mask.sum() else float("nan")
    print(f"\nHybrid model (binary same-material, for apples-to-apples baseline comparison): "
          f"precision={hybrid_precision:.3f} recall={hybrid_recall:.3f} f1={hybrid_f1:.3f}")
    print(f"Hybrid false-merge rate on hard negatives (VARIANT/NOT_A_MATCH): "
          f"{hybrid_false_merge_rate:.3f}  (n={hard_mask.sum()})")

    feat_importance = sorted(zip(FEATURE_NAMES, clf.feature_importances_), key=lambda x: -x[1])
    print("\nFeature importances:")
    for name, imp in feat_importance:
        print(f"  {name:28s} {imp:.4f}")

    os.makedirs(args.out_dir, exist_ok=True)
    joblib.dump(clf, os.path.join(args.out_dir, "classifier.joblib"))
    joblib.dump(word_vec, os.path.join(args.out_dir, "tfidf_word.joblib"))
    joblib.dump(char_vec, os.path.join(args.out_dir, "tfidf_char.joblib"))
    joblib.dump(le, os.path.join(args.out_dir, "label_encoder.joblib"))

    results = {
        "split_sizes": {"train": len(train_df), "val": len(val_df), "test": len(test_df)},
        "baseline": {"threshold": float(best_t), "test_precision": float(baseline_precision),
                     "test_recall": float(baseline_recall), "test_f1": float(baseline_f1),
                     "false_merge_rate_on_hard_negatives": float(baseline_false_merge_rate),
                     "n_hard_negatives": int(hard_mask.sum())},
        "hybrid_model": {"val_macro_f1": float(f1_score(y_val, val_pred, average="macro")),
                          "test_macro_f1": float(f1_score(y_test, test_pred, average="macro")),
                          "test_binary_precision": float(hybrid_precision),
                          "test_binary_recall": float(hybrid_recall),
                          "test_binary_f1": float(hybrid_f1),
                          "false_merge_rate_on_hard_negatives": float(hybrid_false_merge_rate)},
        "confusion_matrix_labels": LABELS,
        "confusion_matrix": cm.tolist(),
        "feature_importances": {name: float(imp) for name, imp in feat_importance},
        "classification_report": report,
    }
    with open(os.path.join(args.out_dir, "training_results.json"), "w") as f:
        json.dump(results, f, indent=2)
    print(f"\nSaved model artifacts + training_results.json to {args.out_dir}")


if __name__ == "__main__":
    main()
