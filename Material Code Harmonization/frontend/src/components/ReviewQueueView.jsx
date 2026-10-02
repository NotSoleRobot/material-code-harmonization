import React, { useState, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  CheckCircle2, CheckCheck, RefreshCw,
  Search, Clock, AlertTriangle, Eye, ChevronRight
} from "lucide-react";
import { api } from "../services/api";
import { useAuth } from "../context/useAuth";
import { CodeChip } from "./common/CodeChip";
import { ConfidenceTierBadge } from "./common/ConfidenceTierBadge";
import { ErrorPanel } from "./common/ErrorPanel";
import { LoadingSkeleton } from "./common/LoadingSkeleton";
import { Table } from "./common/Table";

export function ReviewQueueView() {
  const navigate = useNavigate();
  const { user, hasRole } = useAuth();

  // Filters
  const [activeStatus, setActiveStatus] = useState("PENDING");
  const [tierFilter, setTierFilter] = useState("ALL");
  const [cpseFilter, setCpseFilter] = useState("ALL");
  const [search, setSearch] = useState("");
  const [currentPage, setCurrentPage] = useState(0);

  // Actions
  const [bulkLoading, setBulkLoading] = useState(false);
  const [actionMessage, setActionMessage] = useState(null);
  const [renderTime] = useState(() => Date.now());

  const { data: mappingPage, isLoading: loading, error, refetch: reloadMappings } = useQuery({
    queryKey: ["mappings", activeStatus, currentPage],
    queryFn: () => api.getMappingsPage(activeStatus, currentPage, 50),
  });
  const mappings = useMemo(() => mappingPage?.content || [], [mappingPage]);

  const handleBulkApprove = async () => {
    if (!window.confirm("Are you sure you want to bulk-approve all HIGH confidence (≥85%) mappings in your assigned categories?")) {
      return;
    }
    setBulkLoading(true);
    setActionMessage(null);
    try {
      const res = await api.bulkApproveHighConfidence();
      setActionMessage({
        type: res.skippedCount > 0 ? "warning" : "success",
        text: `Confirmed ${res.approvedCount ?? 0} of ${res.eligibleCount ?? 0} eligible mappings.${res.skippedCount ? ` ${res.skippedCount} skipped: ${res.skipped?.[0]?.reason || "individual review required"}.` : ""}`,
      });
      await reloadMappings();
    } catch (err) {
      setActionMessage({ type: "error", text: err.message || "Bulk approval failed." });
    } finally {
      setBulkLoading(false);
    }
  };

  // SLA Aging calculation
  const getSlaBadge = (createdAt) => {
    if (!createdAt) return null;
    const diffHours = (renderTime - new Date(createdAt).getTime()) / (1000 * 60 * 60);
    if (diffHours > 72) {
      return (
        <span className="badge badge-danger" style={{ fontSize: "0.68rem" }} title="SLA Breached (>72 hours)">
          <Clock size={10} /> Overdue ({Math.round(diffHours)}h)
        </span>
      );
    }
    if (diffHours > 24) {
      return (
        <span className="badge badge-warning" style={{ fontSize: "0.68rem" }} title="SLA Warning (24-72 hours)">
          <Clock size={10} /> {Math.round(diffHours)}h old
        </span>
      );
    }
    return (
      <span className="badge badge-neutral" style={{ fontSize: "0.68rem" }} title="Fresh submission (<24 hours)">
        <Clock size={10} /> Fresh ({Math.max(1, Math.round(diffHours))}h)
      </span>
    );
  };

  // Filtered dataset
  const filtered = useMemo(() => {
    return mappings.filter((m) => {
      const q = search.toLowerCase();
      const matchSearch =
        (m.materialDescription && m.materialDescription.toLowerCase().includes(q)) ||
        (m.standardizedDescription && m.standardizedDescription.toLowerCase().includes(q)) ||
        (m.cpseMaterialCode && m.cpseMaterialCode.toLowerCase().includes(q)) ||
        (m.commonMaterialCode && m.commonMaterialCode.toLowerCase().includes(q)) ||
        (m.cpseName && m.cpseName.toLowerCase().includes(q));

      const score = m.confidenceScore ?? 0;
      let tier = "LOW";
      if (score >= 0.85) tier = "HIGH";
      else if (score >= 0.60) tier = "MEDIUM";

      const matchTier = tierFilter === "ALL" || tier === tierFilter;
      const matchCpse = cpseFilter === "ALL" || (m.cpseName && m.cpseName.toUpperCase() === cpseFilter);

      return matchSearch && matchTier && matchCpse;
    });
  }, [mappings, search, tierFilter, cpseFilter]);

  const highTierCount = mappings.filter((m) => (m.confidenceScore ?? 0) >= 0.85).length;
  const cpseList = Array.from(new Set(mappings.map((m) => m.cpseName).filter(Boolean)));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "1.25rem" }}>
      {/* Page Header */}
      <div className="page-header" style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: "1rem", flexWrap: "wrap" }}>
        <div>
          <h1 className="page-title">Review Queue</h1>
          <p className="page-subtitle">
            Review material matches that need a decision before they can move forward.
          </p>
        </div>
        <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
          {hasRole(["REVIEWER", "SENIOR_REVIEWER"]) && activeStatus === "PENDING" && highTierCount > 0 && (
            <button
              className="btn btn-primary btn-sm"
              onClick={handleBulkApprove}
              disabled={bulkLoading}
            >
              <CheckCheck size={14} /> {bulkLoading ? "Approving..." : `Bulk Approve High Tier (${highTierCount})`}
            </button>
          )}
          <button className="btn btn-ghost btn-sm" onClick={() => reloadMappings()}>
            <RefreshCw size={14} /> Refresh
          </button>
        </div>
      </div>

      {/* Action Notification */}
      {actionMessage && (
        <div
          style={{
            background:
              actionMessage.type === "success"
                ? "var(--success-bg)"
                : actionMessage.type === "warning"
                ? "var(--warning-bg)"
                : "var(--danger-bg)",
            border: `1px solid ${
              actionMessage.type === "success"
                ? "var(--success-border)"
                : actionMessage.type === "warning"
                ? "var(--warning-border)"
                : "var(--danger-border)"
            }`,
            color:
              actionMessage.type === "success"
                ? "var(--success)"
                : actionMessage.type === "warning"
                ? "var(--warning)"
                : "var(--danger)",
            padding: "0.75rem 1rem",
            borderRadius: "var(--radius-sm)",
            fontSize: "0.82rem",
            display: "flex",
            alignItems: "center",
            gap: "0.5rem",
          }}
        >
          {actionMessage.type === "success" ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}
          <span>{actionMessage.text}</span>
        </div>
      )}

      {/* Filter and Control Bar */}
      <div className="card">
        <div className="card-body" style={{ padding: "0.85rem 1.25rem", display: "flex", flexDirection: "column", gap: "0.75rem" }}>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "0.75rem" }}>
            {/* Status Tabs */}
            <div style={{ display: "flex", gap: "0.4rem" }}>
              {[
                { key: "PENDING", label: "Awaiting Review" },
                { key: "CONFIRMED", label: "Confirmed" },
                { key: "REJECTED", label: "Rejected" },
              ].map(({ key, label }) => (
                <button
                  key={key}
                  className={`btn btn-sm ${activeStatus === key ? "btn-primary" : "btn-outline"}`}
                  onClick={() => { setActiveStatus(key); setCurrentPage(0); }}
                >
                  {label}
                  {activeStatus === key && (
                    <span
                      style={{
                        background: "var(--surface)",
                        color: "var(--accent)",
                        fontSize: "0.7rem",
                        padding: "0.05rem 0.4rem",
                        borderRadius: "var(--radius)",
                        marginLeft: "4px",
                      }}
                    >
                      {mappings.length}
                    </span>
                  )}
                </button>
              ))}
            </div>

            {/* Search */}
            <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", flex: "1 1 240px", maxWidth: "360px" }}>
              <Search size={15} color="var(--text-dim)" />
              <input
                type="text"
                placeholder="Search descriptions or codes..."
                className="form-input"
                style={{ fontSize: "0.8rem", padding: "0.35rem 0.65rem" }}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
          </div>

          {/* Secondary Filters: Tier & CPSE */}
          <div style={{ display: "flex", gap: "0.75rem", alignItems: "center", flexWrap: "wrap", borderTop: "1px solid var(--border)", paddingTop: "0.6rem" }}>
            <span style={{ fontSize: "0.75rem", color: "var(--text-muted)", fontWeight: 600 }}>Confidence Tier:</span>
            <div style={{ display: "flex", gap: "0.3rem" }}>
              {[
                { key: "ALL", label: "All Tiers" },
                { key: "HIGH", label: "High (≥85%)" },
                { key: "MEDIUM", label: "Medium (60-85%)" },
                { key: "LOW", label: "Low (<60%)" },
              ].map((t) => (
                <button
                  key={t.key}
                  className={`btn btn-xs ${tierFilter === t.key ? "btn-primary" : "btn-ghost"}`}
                  onClick={() => setTierFilter(t.key)}
                >
                  {t.label}
                </button>
              ))}
            </div>

            {cpseList.length > 1 && (
              <>
                <span style={{ fontSize: "0.75rem", color: "var(--text-muted)", fontWeight: 600, marginLeft: "auto" }}>
                  CPSE Origin:
                </span>
                <select
                  className="form-select"
                  style={{ fontSize: "0.75rem", padding: "0.2rem 0.5rem", width: "130px" }}
                  value={cpseFilter}
                  onChange={(e) => setCpseFilter(e.target.value)}
                >
                  <option value="ALL">All CPSEs</option>
                  {cpseList.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Main Review Cards List */}
      {error ? (
        <ErrorPanel message={error.message || "Failed to load review queue."} onRetry={refetch} />
      ) : loading ? (
        <LoadingSkeleton count={4} />
      ) : filtered.length === 0 ? (
        <div className="card">
          <div className="empty-state">
            <div className="empty-state-icon">
              <CheckCircle2 size={16} color="var(--success)" />
            </div>
            <div className="empty-state-title">
              {activeStatus === "PENDING" ? "Queue is Clear" : `No ${activeStatus.toLowerCase()} mappings found`}
            </div>
            <div className="empty-state-sub">
              {activeStatus === "PENDING"
                ? "All candidate items in your scope have been processed."
                : "Items will appear here once reviewed."}
            </div>
          </div>
        </div>
      ) : (
        <Table caption="Mappings awaiting governance review">
          <thead><tr>
            <th scope="col">Mapping</th><th scope="col">Submitting CPSE</th>
            <th scope="col">Plant material</th><th scope="col">Proposed canonical record</th>
            <th scope="col">Match basis</th><th scope="col">Confidence</th>
            <th scope="col">SLA</th><th scope="col" className="text-right">Action</th>
          </tr></thead>
          <tbody>{filtered.map((m) => {
            const mappingId = m.mappingId || m.id;
            const isOwnCpse = user?.cpse?.name && m.cpseName
              && user.cpse.name.toUpperCase() === m.cpseName.toUpperCase();
            const canApprove = hasRole(["REVIEWER", "SENIOR_REVIEWER"]) && (!isOwnCpse || hasRole("SENIOR_REVIEWER"));
            return <tr key={mappingId}>
              <td className="table-code">#{mappingId}</td>
              <td><span className="badge badge-neutral">{m.cpseName}</span></td>
              <td><div className="font-medium">{m.materialDescription || m.rawDescription}</div><div className="text-xs text-muted font-mono">{m.cpseMaterialCode || "—"}</div></td>
              <td><div>{m.standardizedDescription || m.groupCanonicalName || "Canonical master record"}</div>{m.commonMaterialCode && <CodeChip code={m.commonMaterialCode} />}</td>
              <td>
                <span className="badge badge-info">{m.routingDecision || m.matchBasis || "Not recorded"}</span>
                {m.candidateMargin != null && <div className="text-xs text-muted mt-1">Margin {(Number(m.candidateMargin) * 100).toFixed(1)}%</div>}
              </td>
              <td><ConfidenceTierBadge score={m.confidenceScore} tier={m.confidenceTier} /></td>
              <td>{getSlaBadge(m.createdAt || m.created_at)}</td>
              <td className="text-right">
                <button className="btn btn-outline btn-xs" onClick={() => navigate(`/review/${mappingId}`)}><Eye size={13} /> Inspect <ChevronRight size={13} /></button>
                {activeStatus === "PENDING" && !canApprove && <div className="text-xs text-warning mt-1"><AlertTriangle size={11} /> 4-eyes restriction</div>}
              </td>
            </tr>;
          })}</tbody>
        </Table>
      )}
      {(mappingPage?.totalPages || 0) > 1 && (
        <div style={{ display: "flex", justifyContent: "flex-end", alignItems: "center", gap: "0.6rem" }}>
          <button className="btn btn-outline btn-sm" disabled={currentPage === 0} onClick={() => setCurrentPage((p) => p - 1)}>Previous</button>
          <span className="text-sm text-muted">Page {currentPage + 1} of {mappingPage.totalPages} · {mappingPage.totalElements} mappings</span>
          <button className="btn btn-outline btn-sm" disabled={currentPage + 1 >= mappingPage.totalPages} onClick={() => setCurrentPage((p) => p + 1)}>Next</button>
        </div>
      )}
    </div>
  );
}
