import React from "react";
import { Check, X, AlertTriangle, HelpCircle } from "lucide-react";

export function AttributeComparisonTable({ explanation }) {
  const checks = explanation?.checks || [];
  const warnings = explanation?.warnings || [];
  const conflicts = explanation?.conflicts || [];

  return (
    <div className="attribute-comparison-card">
      <div className="comp-table-wrapper">
        <table className="comp-table" aria-label="Attribute-level AI Match Verification Table">
          <caption>Attribute evidence used to assess the proposed match</caption>
          <thead>
            <tr>
              <th scope="col" style={{ width: "25%" }}>Extracted Parameter</th>
              <th scope="col" style={{ width: "32%" }}>CPSE Plant Record</th>
              <th scope="col" style={{ width: "32%" }}>Canonical NUMM Record</th>
              <th scope="col" style={{ width: "11%" }}>Verification</th>
            </tr>
          </thead>
          <tbody>
            {checks.map((c, i) => {
              const parts = c.split(":");
              const label = parts[0]?.replace(/^Same\s+/i, "") || "Attribute";
              const val = parts.slice(1).join(":").trim() || "Matches";
              return (
                <tr key={`check-${i}`} className="row-agree">
                  <td className="param-label">{label}</td>
                  <td className="param-val">{val}</td>
                  <td className="param-val">{val}</td>
                  <td>
                    <span className="badge badge-agree" title="Attribute values agree">
                      <Check size={11} aria-hidden="true" /> Agree
                    </span>
                  </td>
                </tr>
              );
            })}

            {conflicts.map((c, i) => {
              const isCrit = c.toLowerCase().includes("identity_critical");
              return (
                <tr key={`conflict-${i}`} className={`row-conflict ${isCrit ? "critical" : ""}`}>
                  <td className="param-label font-bold text-danger">
                    {c.split(":")[0] || "Attribute Conflict"}
                  </td>
                  <td className="param-val" colSpan={2}>
                    <span className="conflict-text">{c}</span>
                  </td>
                  <td>
                    <span className="badge badge-conflict" title="Attribute conflict detected">
                      <X size={11} aria-hidden="true" /> Conflict
                    </span>
                  </td>
                </tr>
              );
            })}

            {warnings.map((w, i) => (
              <tr key={`warn-${i}`} className="row-warning">
                <td className="param-label text-muted">{w.split(" ")[0] || "Attribute"}</td>
                <td className="param-val text-dim" colSpan={2}>
                  {w}
                </td>
                <td>
                  <span className="badge badge-warning" title="Attribute not stated on one side">
                    <AlertTriangle size={11} aria-hidden="true" /> Unverified
                  </span>
                </td>
              </tr>
            ))}

            {checks.length === 0 && conflicts.length === 0 && warnings.length === 0 && (
              <tr>
                <td colSpan={4} className="text-center text-muted p-4">
                  <HelpCircle size={16} style={{ display: "inline", verticalAlign: "middle", marginRight: "4px" }} />
                  Textual description similarity verified by TF-IDF embedding vector.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* AI Evidence Summary Callout */}
      <div className="ai-summary-bar">
        <div className="summary-item">
          <span className="summary-count text-success">{checks.length}</span>
          <span className="summary-label">Confirmed Checks</span>
        </div>
        <div className="summary-divider" />
        <div className="summary-item">
          <span className="summary-count text-danger">{conflicts.length}</span>
          <span className="summary-label">Conflicts Flagged</span>
        </div>
        <div className="summary-divider" />
        <div className="summary-item">
          <span className="summary-count text-warning">{warnings.length}</span>
          <span className="summary-label">Unstated Attributes</span>
        </div>
      </div>
    </div>
  );
}
