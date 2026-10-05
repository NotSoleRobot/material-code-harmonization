import React, { useState, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Clock, CheckCircle2, XCircle, Search, Download, Eye,
  User, RefreshCw, X, ShieldCheck, ShieldAlert, Layers
} from "lucide-react";
import { api } from "../services/api";
import { ErrorPanel } from "./common/ErrorPanel";
import { LoadingSkeleton } from "./common/LoadingSkeleton";
import { Table } from "./common/Table";

const ACTION_FILTERS = [
  { key: "REVIEW_DECISIONS", label: "Review Decisions" },
  { key: "ALL", label: "All Events" },
  { key: "MAPPING_CONFIRMED", label: "Approvals" },
  { key: "MAPPING_REJECTED", label: "Rejections" },
  { key: "MAPPING_SUPERSEDED", label: "Admin Overrides" },
];

// Events that count as manual governance decisions
const REVIEW_DECISION_ACTIONS = new Set([
  "MAPPING_CONFIRMED", "MAPPING_REJECTED", "MAPPING_SUPERSEDED",
  "MAPPING_EDITED", "BULK_APPROVAL",
]);

export function AuditTrailView() {
  const [search, setSearch] = useState("");
  const [actionFilter, setActionFilter] = useState("REVIEW_DECISIONS");
  const [selectedAudit, setSelectedAudit] = useState(null);

  // Cryptographic Verification state
  const [verifying, setVerifying] = useState(false);
  const [verificationResult, setVerificationResult] = useState(null);
  const [showVerifyModal, setShowVerifyModal] = useState(false);

  const { data: logs = [], isLoading: loading, error: logsError, refetch: loadLogs } = useQuery({
    queryKey: ["auditTrail", actionFilter],
    queryFn: () => api.getAuditTrail(
      (actionFilter === "ALL" || actionFilter === "REVIEW_DECISIONS") ? "" : actionFilter
    ),
  });
  const error = logsError?.message;

  const handleVerifyChain = async () => {
    setVerifying(true);
    setShowVerifyModal(true);
    try {
      const res = await api.verifyAuditChain();
      setVerificationResult(res);
    } catch (err) {
      setVerificationResult({
        status: "FAILED",
        chainIntact: false,
        message: err.message || "Verification endpoint failed.",
      });
    } finally {
      setVerifying(false);
    }
  };

  const filteredLogs = useMemo(() => {
    const q = search.toLowerCase();
    return logs.filter((l) => {
      const matchSearch =
        (l.username && l.username.toLowerCase().includes(q)) ||
        (l.action && l.action.toLowerCase().includes(q)) ||
        (l.entityType && l.entityType.toLowerCase().includes(q)) ||
        (l.newValue && l.newValue.toLowerCase().includes(q)) ||
        (l.oldValue && l.oldValue.toLowerCase().includes(q)) ||
        String(l.entityId).includes(q) ||
        String(l.auditId).includes(q);

      const matchAction =
        actionFilter === "ALL"
          ? true
          : actionFilter === "REVIEW_DECISIONS"
          ? REVIEW_DECISION_ACTIONS.has(l.action)
          : l.action === actionFilter;

      return matchSearch && matchAction;
    });
  }, [logs, search, actionFilter]);

  const handleExportCsv = () => {
    const headers = ["Audit ID", "Timestamp", "Actor / User", "Action", "Entity Type", "Entity ID", "Old Value", "New Value & Justification", "Row Hash"];
    const csvRows = filteredLogs.map((l) => [
      l.auditId,
      `"${new Date(l.timestamp).toISOString()}"`,
      `"${l.username || ""}"`,
      `"${l.action || ""}"`,
      `"${l.entityType || ""}"`,
      l.entityId,
      `"${(l.oldValue || "").replace(/"/g, '""')}"`,
      `"${(l.newValue || "").replace(/"/g, '""')}"`,
      `"${l.rowHash || ""}"`,
    ]);
    const csv = "data:text/csv;charset=utf-8," + [headers.join(","), ...csvRows.map(r => r.join(","))].join("\n");
    const link = document.createElement("a");
    link.setAttribute("href", encodeURI(csv));
    link.setAttribute("download", `NUMM_audit_trail_${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const actionBadge = (action) => {
    if (!action) return null;
    if (action.includes("CONFIRMED") || action.includes("APPROVED"))
      return <span className="badge badge-success" style={{ gap: "4px" }}><CheckCircle2 size={11} /> Approved</span>;
    if (action.includes("REJECTED"))
      return <span className="badge badge-danger" style={{ gap: "4px" }}><XCircle size={11} /> Rejected</span>;
    if (action.includes("SUPERSEDED"))
      return <span className="badge badge-warning" style={{ gap: "4px" }}><ShieldAlert size={11} /> Override</span>;
    if (action.includes("INGEST"))
      return <span className="badge badge-info" style={{ gap: "4px" }}><Layers size={11} /> Ingestion</span>;
    return <span className="badge badge-neutral" style={{ gap: "4px" }}><ShieldCheck size={11} /> System Event</span>;
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "1.25rem" }}>
      {/* Header */}
      <div className="page-header" style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: "1rem", flexWrap: "wrap" }}>
        <div>
          <h1 className="page-title">Audit Trail</h1>
          <p className="page-subtitle">
            Review material ingestion, harmonization, and technical decision events.
          </p>
        </div>
        <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
          <button className="btn btn-outline btn-sm" onClick={handleVerifyChain} title="Verify SHA-256 Hash Chain">
            <ShieldCheck size={14} color="var(--success)" /> Verify audit chain
          </button>
          <button className="btn btn-ghost btn-sm" onClick={loadLogs}>
            <RefreshCw size={14} /> Refresh
          </button>
          <button className="btn btn-primary btn-sm" onClick={handleExportCsv} disabled={filteredLogs.length === 0}>
            <Download size={14} /> Export CSV
          </button>
        </div>
      </div>

      {/* Filter and Search Bar */}
      <div className="card">
        <div className="card-body" style={{ padding: "0.85rem 1.25rem", display: "flex", alignItems: "center", gap: "1rem", flexWrap: "wrap" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "0.6rem", flex: "1 1 300px" }}>
            <Search size={16} color="var(--text-dim)" style={{ flexShrink: 0 }} />
            <input
              type="text"
              placeholder="Search by user, record, action, or reason..."
              className="form-input"
              style={{ border: "none", background: "transparent", padding: 0, boxShadow: "none" }}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>

          <div style={{ display: "flex", gap: "0.35rem", flexWrap: "wrap" }}>
            {ACTION_FILTERS.map((f) => (
              <button
                key={f.key}
                className={`btn btn-xs ${actionFilter === f.key ? "btn-primary" : "btn-outline"}`}
                onClick={() => setActionFilter(f.key)}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Table Content */}
      {error ? (
        <ErrorPanel message={error} onRetry={loadLogs} />
      ) : loading ? (
        <LoadingSkeleton count={5} />
      ) : (
        <Table caption="Governance audit trail">
            <thead>
              <tr>
                <th scope="col">Audit #</th><th scope="col">Timestamp</th>
                <th scope="col">Action</th><th scope="col">Authorized Actor</th>
                <th scope="col">Entity</th><th scope="col">New State & Justification Notes</th>
                <th scope="col">Chained Hash</th><th scope="col">Inspect</th>
              </tr>
            </thead>
            <tbody>
              {filteredLogs.length === 0 ? (
                <tr>
                  <td colSpan={8} style={{ textAlign: "center", padding: "3rem", color: "var(--text-muted)" }}>
                    No audit records match your query.
                  </td>
                </tr>
              ) : (
                filteredLogs.map((l) => (
                  <tr key={l.auditId}>
                    <td>
                      <span className="text-mono text-xs" style={{ color: "var(--text-dim)" }}>
                        #{l.auditId}
                      </span>
                    </td>
                    <td style={{ fontSize: "0.78rem", color: "var(--text-secondary)", whiteSpace: "nowrap" }}>
                      <div style={{ display: "flex", alignItems: "center", gap: "4px" }}>
                        <Clock size={12} color="var(--text-dim)" />
                        {new Date(l.timestamp).toLocaleDateString()} {new Date(l.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })}
                      </div>
                    </td>
                    <td>{actionBadge(l.action)}</td>
                    <td>
                      <div style={{ display: "flex", alignItems: "center", gap: "5px" }}>
                        <User size={13} color="var(--accent)" style={{ flexShrink: 0 }} />
                        <span style={{ fontSize: "0.8rem", fontWeight: 600, color: "var(--text-primary)" }}>
                          {l.username || "System (Automated)"}
                        </span>
                      </div>
                    </td>
                    <td>
                      <span className="badge badge-neutral" style={{ fontFamily: "var(--font-mono)", fontSize: "0.7rem" }}>
                        {l.entityType} #{l.entityId}
                      </span>
                    </td>
                    <td style={{ maxWidth: "320px" }}>
                      <span className="truncate" style={{ display: "block", fontSize: "0.8rem", color: "var(--text-secondary)" }}>
                        {l.newValue}
                      </span>
                      {l.oldValue && l.oldValue !== "null" && (
                        <span style={{ fontSize: "0.7rem", color: "var(--text-dim)", display: "block" }}>
                          Previous: {l.oldValue}
                        </span>
                      )}
                    </td>
                    <td>
                      <span
                        className="text-mono text-xs"
                        style={{
                          display: "inline-block",
                          maxWidth: "100px",
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                          whiteSpace: "nowrap",
                          background: "var(--bg-subtle)",
                          padding: "0.1rem 0.35rem",
                          borderRadius: "var(--radius-xs)",
                          border: "1px solid var(--border)",
                          color: "var(--text-muted)",
                        }}
                        title={l.rowHash || "Chained SHA-256 Hash"}
                      >
                        {l.rowHash ? `${l.rowHash.slice(0, 10)}...` : "—"}
                      </span>
                    </td>
                    <td>
                      <button
                        className="btn btn-ghost btn-xs"
                        onClick={() => setSelectedAudit(l)}
                        title="Inspect full audit event metadata"
                        style={{ padding: "0.25rem 0.4rem" }}
                      >
                        <Eye size={13} />
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
        </Table>
      )}

      {/* Cryptographic Chain Integrity Modal */}
      {showVerifyModal && (
        <div
          style={{
            position: "fixed",
            inset: 0,
            background: "var(--overlay)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1000,
            padding: "1rem",
          }}
        >
          <div className="card" style={{ width: "100%", maxWidth: "560px", boxShadow: "var(--shadow)" }}>
            <div className="card-header" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <div className="card-title">
                <ShieldCheck size={16} color="var(--success)" />
                Audit Trail Cryptographic Verification
              </div>
              <button className="btn btn-ghost btn-xs" onClick={() => setShowVerifyModal(false)}>
                <X size={16} />
              </button>
            </div>
            <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "1rem" }}>
              {verifying ? (
                <div style={{ textAlign: "center", padding: "2rem 0" }}>
                  <RefreshCw size={16} color="var(--accent)" style={{ margin: "0 auto 1rem auto", display: "block" }} />
                  <div style={{ fontWeight: 600 }}>Recomputing Chained SHA-256 Row Hashes...</div>
                  <div style={{ fontSize: "0.8rem", color: "var(--text-muted)", marginTop: "4px" }}>
                    Validating previous_hash link integrity from Genesis block to latest entry
                  </div>
                </div>
              ) : verificationResult ? (
                <div>
                  <div
                    style={{
                      background: (verificationResult.valid ?? verificationResult.chainIntact) ? "var(--success-bg)" : "var(--danger-bg)",
                      border: `1px solid ${(verificationResult.valid ?? verificationResult.chainIntact) ? "var(--success-border)" : "var(--danger-border)"}`,
                      borderRadius: "var(--radius-md)",
                      padding: "1rem",
                      display: "flex",
                      alignItems: "flex-start",
                      gap: "0.75rem",
                      marginBottom: "1rem",
                    }}
                  >
                    {(verificationResult.valid ?? verificationResult.chainIntact) ? (
                      <CheckCircle2 size={16} color="var(--success)" style={{ flexShrink: 0, marginTop: "2px" }} />
                    ) : (
                      <ShieldAlert size={16} color="var(--danger)" style={{ flexShrink: 0, marginTop: "2px" }} />
                    )}
                    <div>
                      <div
                        style={{
                          fontWeight: 700,
                          fontSize: "0.95rem",
                          color: (verificationResult.valid ?? verificationResult.chainIntact) ? "var(--success)" : "var(--danger)",
                        }}
                      >
                        {(verificationResult.valid ?? verificationResult.chainIntact)
                          ? "Cryptographic Chain Verified — 100% Intact"
                          : "Integrity Violation Detected"}
                      </div>
                      <div style={{ fontSize: "0.82rem", color: "var(--text-secondary)", marginTop: "4px" }}>
                        {verificationResult.message || `All ${verificationResult.chainLength ?? verificationResult.verifiedCount ?? logs.length} audit records verified against chained SHA-256 hashes.`}
                      </div>
                    </div>
                  </div>

                  <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem", fontSize: "0.8rem" }}>
                    <div style={{ background: "var(--bg-subtle)", padding: "0.6rem 0.75rem", borderRadius: "var(--radius-sm)" }}>
                      <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase" }}>Verified Records</div>
                      <div style={{ fontWeight: 700, fontSize: "1.1rem", marginTop: "2px" }}>
                        {verificationResult.verifiedCount ?? logs.length}
                      </div>
                    </div>
                    <div style={{ background: "var(--bg-subtle)", padding: "0.6rem 0.75rem", borderRadius: "var(--radius-sm)" }}>
                      <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase" }}>Algorithm</div>
                      <div style={{ fontWeight: 600, fontFamily: "var(--font-mono)", marginTop: "2px" }}>
                        Chained SHA-256
                      </div>
                    </div>
                  </div>
                </div>
              ) : null}
            </div>
            <div className="card-footer" style={{ display: "flex", justifyContent: "flex-end", padding: "0.75rem 1.25rem", borderTop: "1px solid var(--border)" }}>
              <button className="btn btn-primary btn-sm" onClick={() => setShowVerifyModal(false)}>
                Done
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Audit Detail Inspector Modal */}
      {selectedAudit && (
        <div
          style={{
            position: "fixed",
            inset: 0,
            background: "var(--overlay)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1000,
            padding: "1rem",
          }}
        >
          <div className="card" style={{ width: "100%", maxWidth: "600px", boxShadow: "var(--shadow)" }}>
            <div className="card-header" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <div className="card-title">
                <ShieldCheck size={16} color="var(--accent)" />
                Audit Record Inspector #{selectedAudit.auditId}
              </div>
              <button className="btn btn-ghost btn-xs" onClick={() => setSelectedAudit(null)}>
                <X size={16} />
              </button>
            </div>
            <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "1rem", fontSize: "0.825rem" }}>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
                <div style={{ background: "var(--bg-subtle)", padding: "0.6rem 0.75rem", borderRadius: "var(--radius-sm)" }}>
                  <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase" }}>Event Action</div>
                  <div style={{ fontWeight: 700, marginTop: "2px" }}>{selectedAudit.action}</div>
                </div>
                <div style={{ background: "var(--bg-subtle)", padding: "0.6rem 0.75rem", borderRadius: "var(--radius-sm)" }}>
                  <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase" }}>Timestamp (ISO)</div>
                  <div style={{ fontWeight: 600, fontFamily: "var(--font-mono)", fontSize: "0.75rem", marginTop: "2px" }}>
                    {selectedAudit.timestamp}
                  </div>
                </div>
                <div style={{ background: "var(--bg-subtle)", padding: "0.6rem 0.75rem", borderRadius: "var(--radius-sm)" }}>
                  <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase" }}>Authorized User</div>
                  <div style={{ fontWeight: 600, marginTop: "2px" }}>{selectedAudit.username || "System"}</div>
                </div>
                <div style={{ background: "var(--bg-subtle)", padding: "0.6rem 0.75rem", borderRadius: "var(--radius-sm)" }}>
                  <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase" }}>Target Entity</div>
                  <div style={{ fontWeight: 600, fontFamily: "var(--font-mono)", marginTop: "2px" }}>
                    {selectedAudit.entityType} (ID: {selectedAudit.entityId})
                  </div>
                </div>
              </div>

              {/* Hashes */}
              <div style={{ background: "var(--bg-subtle)", padding: "0.75rem", borderRadius: "var(--radius-sm)" }}>
                <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase", marginBottom: "4px" }}>
                  Cryptographic Hashes
                </div>
                <div style={{ display: "flex", flexDirection: "column", gap: "0.3rem", fontFamily: "var(--font-mono)", fontSize: "0.72rem" }}>
                  <div>
                    <span style={{ color: "var(--text-dim)" }}>prev_hash: </span>
                    <span style={{ color: "var(--text-secondary)" }}>{selectedAudit.prevHash || "GENESIS_ROOT"}</span>
                  </div>
                  <div>
                    <span style={{ color: "var(--text-dim)" }}>row_hash: </span>
                    <span style={{ color: "var(--accent)" }}>{selectedAudit.rowHash || "—"}</span>
                  </div>
                </div>
              </div>

              {/* State Transition Snapshot */}
              <div>
                <div style={{ fontWeight: 700, marginBottom: "0.4rem", color: "var(--text-secondary)" }}>State Transition Snapshot:</div>
                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.5rem" }}>
                  <div style={{ background: "var(--danger-bg)", border: "1px solid var(--danger-border)", borderRadius: "var(--radius-sm)", padding: "0.65rem 0.85rem" }}>
                    <div style={{ fontSize: "0.7rem", fontWeight: 700, color: "var(--danger)" }}>Previous State (Before)</div>
                    <div style={{ fontFamily: "var(--font-mono)", fontSize: "0.78rem", marginTop: "4px", color: "var(--text-secondary)" }}>
                      {selectedAudit.oldValue || "None (Initial State)"}
                    </div>
                  </div>
                  <div style={{ background: "var(--success-bg)", border: "1px solid var(--success-border)", borderRadius: "var(--radius-sm)", padding: "0.65rem 0.85rem" }}>
                    <div style={{ fontSize: "0.7rem", fontWeight: 700, color: "var(--success)" }}>Governed State (After)</div>
                    <div style={{ fontFamily: "var(--font-mono)", fontSize: "0.78rem", marginTop: "4px", color: "var(--text-primary)" }}>
                      {selectedAudit.newValue}
                    </div>
                  </div>
                </div>
              </div>

              <div style={{ display: "flex", justifyContent: "flex-end", marginTop: "0.5rem" }}>
                <button className="btn btn-outline btn-sm" onClick={() => setSelectedAudit(null)}>
                  Close Inspector
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
