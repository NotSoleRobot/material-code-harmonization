import React, { useState } from "react";
import {
  Check, AlertTriangle, XCircle,
  Play, RefreshCw, CheckCircle2,
  Info, Cpu, FileText
} from "lucide-react";
import { api } from "../services/api";
import { ConfidenceTierBadge } from "./common/ConfidenceTierBadge";
import { ErrorPanel } from "./common/ErrorPanel";

const PRESETS = [
  {
    name: "Carbon Steel Pipes (ONGC vs IOCL)",
    desc: "Identical DN50 pipes: ASTM A106 Gr.B vs IS 1239 equivalent standards",
    matA: {
      description: "CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB",
      category: "PIPE",
      specification: "ASTM A106 GR.B",
    },
    matB: {
      description: "CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239",
      category: "PIPE",
      specification: "IS 1239",
    },
  },
  {
    name: "Gate Valves (GAIL vs IOCL)",
    desc: "Class 150 2-Inch RF flanged valve compatibility across API 600 & ASME standards",
    matA: {
      description: "GATE VALVE CS CLASS150 2INCH RF API600",
      category: "VALVE",
      specification: "API 600",
    },
    matB: {
      description: "GATE VALVE 2 INCH 150# RF FLANGED CS",
      category: "VALVE",
      specification: "ASME B16.34",
    },
  },
  {
    name: "Ball Bearings (BHEL vs NTPC)",
    desc: "Standard 6205 2RS deep groove ball bearing from SKF vs FAG equivalent",
    matA: {
      description: "DEEP GROOVE BALL BEARING 6205-2RS1 SKF",
      category: "BEARING",
      specification: "SKF 6205-2RS",
    },
    matB: {
      description: "BALL BEARING 6205 2RS FAG 25X52X15 MM",
      category: "BEARING",
      specification: "DIN 625",
    },
  },
  {
    name: "Cross-Domain Negative Check (Bearing vs Valve)",
    desc: "Incompatible commodity categories to test rejection threshold",
    matA: {
      description: "BALL BEARING 6205 2RS SKF",
      category: "BEARING",
      specification: "SKF",
    },
    matB: {
      description: "GATE VALVE 2 INCH 150# RF FLANGED",
      category: "VALVE",
      specification: "API 600",
    },
  },
];

export function HarmonizeView() {
  const [descA, setDescA] = useState(PRESETS[0].matA.description);
  const [catA, setCatA] = useState(PRESETS[0].matA.category);
  const [specA, setSpecA] = useState(PRESETS[0].matA.specification);

  const [descB, setDescB] = useState(PRESETS[0].matB.description);
  const [catB, setCatB] = useState(PRESETS[0].matB.category);
  const [specB, setSpecB] = useState(PRESETS[0].matB.specification);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  const handleLoadPreset = (p) => {
    setDescA(p.matA.description);
    setCatA(p.matA.category);
    setSpecA(p.matA.specification);

    setDescB(p.matB.description);
    setCatB(p.matB.category);
    setSpecB(p.matB.specification);
    setResult(null);
    setError(null);
  };

  const handleCompare = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const resp = await api.compareMaterials(
        { description: descA, category: catA, specification: specA },
        { description: descB, category: catB, specification: specB }
      );
      setResult(resp);
    } catch (err) {
      // Honest error reporting — no mock fallback
      setError(err.message || "Failed to execute material comparison against ML engine.");
    } finally {
      setLoading(false);
    }
  };

  const relBadge = (rel) => {
    if (!rel) return null;
    if (rel === "EXACT_DUPLICATE" || rel === "NEAR_DUPLICATE" || rel === "FUNCTIONALLY_EQUIVALENT" || rel === "SAME_MATERIAL") {
      return (
        <span className="badge badge-success" style={{ fontSize: "0.85rem", padding: "0.3rem 0.65rem" }}>
          <CheckCircle2 size={13} /> {rel.replace(/_/g, " ")}
        </span>
      );
    }
    if (rel === "VARIANT" || rel === "NEEDS_REVIEW") {
      return (
        <span className="badge badge-warning" style={{ fontSize: "0.85rem", padding: "0.3rem 0.65rem" }}>
          <AlertTriangle size={13} /> {rel.replace(/_/g, " ")}
        </span>
      );
    }
    return (
      <span className="badge badge-danger" style={{ fontSize: "0.85rem", padding: "0.3rem 0.65rem" }}>
        <XCircle size={13} /> {rel.replace(/_/g, " ")}
      </span>
    );
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "1.25rem" }}>
      {/* Header */}
      <div className="page-header">
        <h1 className="page-title">Material Specification Comparison</h1>
        <p className="page-subtitle">
          Interactive pairwise ML comparison engine for CPSE procurement officers to verify attribute equivalence and cross-enterprise interchangeability.
        </p>
      </div>

      {/* Context Banner */}
      <div
        style={{
          display: "flex",
          gap: "0.75rem",
          alignItems: "flex-start",
          background: "var(--info-bg)",
          border: "1px solid var(--info-border)",
          borderRadius: "var(--radius-md)",
          padding: "0.9rem 1.15rem",
        }}
      >
        <Info size={16} color="var(--info)" style={{ flexShrink: 0, marginTop: "2px" }} />
        <div style={{ fontSize: "0.825rem", color: "var(--info)", lineHeight: 1.5 }}>
          <strong>Pairwise Matching Architecture:</strong> The Python ML engine evaluates text n-grams (TF-IDF), token cosine similarity, and regex-extracted technical attributes (nominal diameter, pressure rating, schedule, material grade) to produce a composite Random Forest confidence score.
        </div>
      </div>

      {/* Preset Benchmarks */}
      <div className="card">
        <div className="card-header" style={{ padding: "0.75rem 1.25rem" }}>
          <div className="card-title" style={{ fontSize: "0.85rem" }}>
            <Cpu size={15} color="var(--accent)" /> Load Tested CPSE Specification Benchmarks:
          </div>
        </div>
        <div className="card-body" style={{ padding: "0.85rem 1.25rem", display: "flex", gap: "0.6rem", flexWrap: "wrap" }}>
          {PRESETS.map((p, i) => (
            <button
              key={i}
              className="btn btn-outline btn-sm"
              onClick={() => handleLoadPreset(p)}
              style={{
                textAlign: "left",
                display: "flex",
                flexDirection: "column",
                alignItems: "flex-start",
                padding: "0.4rem 0.75rem",
              }}
            >
              <span style={{ fontWeight: 600, fontSize: "0.8rem", color: "var(--text-primary)" }}>{p.name}</span>
              <span style={{ fontSize: "0.68rem", color: "var(--text-muted)" }}>{p.desc}</span>
            </button>
          ))}
        </div>
      </div>

      {/* Inputs (Side by Side) */}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1.25rem" }}>
        {/* Specification A */}
        <div className="card">
          <div className="card-header" style={{ borderBottom: "2px solid var(--accent)", padding: "0.75rem 1.25rem" }}>
            <div className="card-title">
              <FileText size={16} color="var(--accent)" />
              Material Specification A (e.g. ONGC / BHEL)
            </div>
          </div>
          <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "0.85rem" }}>
            <div>
              <label className="form-label" style={{ fontSize: "0.75rem" }}>Commodity Category</label>
              <input
                type="text"
                className="form-input"
                value={catA}
                onChange={(e) => setCatA(e.target.value)}
                placeholder="e.g. PIPE, VALVE, BEARING"
              />
            </div>
            <div>
              <label className="form-label" style={{ fontSize: "0.75rem" }}>Raw Material Description *</label>
              <textarea
                className="form-input"
                rows={3}
                value={descA}
                onChange={(e) => setDescA(e.target.value)}
                placeholder="Enter description text..."
                style={{ resize: "vertical" }}
              />
            </div>
            <div>
              <label className="form-label" style={{ fontSize: "0.75rem" }}>Standard / Specification</label>
              <input
                type="text"
                className="form-input"
                value={specA}
                onChange={(e) => setSpecA(e.target.value)}
                placeholder="e.g. ASTM A106, IS 1239, API 600"
              />
            </div>
          </div>
        </div>

        {/* Specification B */}
        <div className="card">
          <div className="card-header" style={{ borderBottom: "2px solid var(--info)", padding: "0.75rem 1.25rem" }}>
            <div className="card-title">
              <FileText size={16} color="var(--info)" />
              Material Specification B (e.g. IOCL / GAIL)
            </div>
          </div>
          <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "0.85rem" }}>
            <div>
              <label className="form-label" style={{ fontSize: "0.75rem" }}>Commodity Category</label>
              <input
                type="text"
                className="form-input"
                value={catB}
                onChange={(e) => setCatB(e.target.value)}
                placeholder="e.g. PIPE, VALVE, BEARING"
              />
            </div>
            <div>
              <label className="form-label" style={{ fontSize: "0.75rem" }}>Raw Material Description *</label>
              <textarea
                className="form-input"
                rows={3}
                value={descB}
                onChange={(e) => setDescB(e.target.value)}
                placeholder="Enter description text..."
                style={{ resize: "vertical" }}
              />
            </div>
            <div>
              <label className="form-label" style={{ fontSize: "0.75rem" }}>Standard / Specification</label>
              <input
                type="text"
                className="form-input"
                value={specB}
                onChange={(e) => setSpecB(e.target.value)}
                placeholder="e.g. IS 1239, ASME B16.34, DIN 625"
              />
            </div>
          </div>
        </div>
      </div>

      {/* Trigger Button */}
      <div style={{ textAlign: "center", margin: "0.5rem 0" }}>
        <button
          className="btn btn-primary btn-lg"
          disabled={loading || !descA.trim() || !descB.trim()}
          onClick={handleCompare}
          style={{ minWidth: "260px" }}
        >
          {loading ? (
            <>
              <RefreshCw size={16} />
              Executing ML Compatibility Pipeline...
            </>
          ) : (
            <>
              <Play size={16} />
              Run Specification Compatibility Check
            </>
          )}
        </button>
      </div>

      {error && <ErrorPanel message={error} onRetry={handleCompare} />}

      {/* Results Section */}
      {result && (
        <div className="card" style={{ borderTop: "3px solid var(--accent)" }}>
          <div
            className="card-header"
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              flexWrap: "wrap",
              gap: "0.5rem",
            }}
          >
            <div className="card-title">
              <Cpu size={16} color="var(--accent)" />
              Match Assessment & Explanation
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
              {relBadge(result.predicted_relationship || result.relationship)}
              <ConfidenceTierBadge
                score={result.confidence || result.confidence_score || result.confidenceScore || 0}
                tier={result.confidence_tier || result.confidenceTier}
              />
            </div>
          </div>

          <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "1.25rem" }}>
            {/* Explainability Checks */}
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1rem" }}>
              {/* Positive Checks */}
              <div
                style={{
                  background: "var(--success-bg)",
                  border: "1px solid var(--success-border)",
                  borderRadius: "var(--radius-sm)",
                  padding: "0.85rem 1rem",
                }}
              >
                <div
                  style={{
                    fontSize: "0.8rem",
                    fontWeight: 700,
                    color: "var(--success)",
                    display: "flex",
                    alignItems: "center",
                    gap: "0.4rem",
                    marginBottom: "0.5rem",
                  }}
                >
                  <CheckCircle2 size={14} /> Verified Equivalent Attributes ({result.explanation?.checks?.length || 0})
                </div>
                {result.explanation?.checks?.length > 0 ? (
                  result.explanation.checks.map((c, i) => (
                    <div
                      key={i}
                      style={{
                        fontSize: "0.78rem",
                        color: "var(--text-secondary)",
                        padding: "0.2rem 0",
                        display: "flex",
                        gap: "0.4rem",
                      }}
                    >
                      <Check size={12} color="var(--success)" style={{ flexShrink: 0, marginTop: "2px" }} />
                      <span>{c}</span>
                    </div>
                  ))
                ) : (
                  <div style={{ fontSize: "0.78rem", color: "var(--text-dim)", fontStyle: "italic" }}>
                    No direct attribute matches found.
                  </div>
                )}
              </div>

              {/* Warnings / Conflicts */}
              <div
                style={{
                  background: "var(--warning-bg)",
                  border: "1px solid var(--warning-border)",
                  borderRadius: "var(--radius-sm)",
                  padding: "0.85rem 1rem",
                }}
              >
                <div
                  style={{
                    fontSize: "0.8rem",
                    fontWeight: 700,
                    color: "var(--warning)",
                    display: "flex",
                    alignItems: "center",
                    gap: "0.4rem",
                    marginBottom: "0.5rem",
                  }}
                >
                  <AlertTriangle size={14} /> Discrepancies & Divergences (
                  {(result.explanation?.warnings?.length || 0) + (result.explanation?.conflicts?.length || 0)})
                </div>
                {result.explanation?.warnings?.map((w, i) => (
                  <div key={i} style={{ fontSize: "0.78rem", color: "var(--warning)", padding: "0.2rem 0" }}>
                    • {w}
                  </div>
                ))}
                {result.explanation?.conflicts?.map((c, i) => (
                  <div key={i} style={{ fontSize: "0.78rem", color: "var(--danger)", padding: "0.2rem 0", fontWeight: 600 }}>
                    • {c}
                  </div>
                ))}
                {!result.explanation?.warnings?.length && !result.explanation?.conflicts?.length && (
                  <div style={{ fontSize: "0.78rem", color: "var(--text-dim)", fontStyle: "italic" }}>
                    No conflicts detected. Specifications are functionally compatible.
                  </div>
                )}
              </div>
            </div>

            {/* Class Probabilities Bar */}
            {result.class_probabilities && (
              <div
                style={{
                  background: "var(--bg-subtle)",
                  padding: "0.85rem 1rem",
                  borderRadius: "var(--radius-sm)",
                  border: "1px solid var(--border)",
                }}
              >
                <div
                  style={{
                    fontSize: "0.75rem",
                    fontWeight: 700,
                    color: "var(--text-muted)",
                    textTransform: "uppercase",
                    marginBottom: "0.6rem",
                  }}
                >
                  Random Forest Classification Probability Distribution:
                </div>
                <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(130px, 1fr))", gap: "0.6rem" }}>
                  {Object.entries(result.class_probabilities).map(([cls, prob]) => (
                    <div
                      key={cls}
                      style={{
                        background: "var(--bg-surface)",
                        padding: "0.5rem",
                        borderRadius: "var(--radius-xs)",
                        border: "1px solid var(--border)",
                      }}
                    >
                      <div style={{ fontSize: "0.68rem", color: "var(--text-dim)", textTransform: "uppercase" }}>
                        {cls.replace(/_/g, " ")}
                      </div>
                      <div
                        style={{
                          fontSize: "0.85rem",
                          fontWeight: 700,
                          color: prob > 0.5 ? "var(--accent)" : "var(--text-primary)",
                          fontFamily: "var(--font-mono)",
                          marginTop: "2px",
                        }}
                      >
                        {(prob * 100).toFixed(1)}%
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
