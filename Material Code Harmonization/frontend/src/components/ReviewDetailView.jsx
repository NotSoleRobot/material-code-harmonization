import React, { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useParams, useNavigate } from "react-router-dom";
import {
  CheckCircle2, XCircle, ArrowLeft, ShieldAlert,
  Layers, Clock, Edit3, AlertTriangle, Building2,
  Check, Cpu
} from "lucide-react";
import { api } from "../services/api";
import { useAuth } from "../context/useAuth";
import { CodeChip } from "./common/CodeChip";
import { ConfidenceTierBadge } from "./common/ConfidenceTierBadge";
import { AttributeComparisonTable } from "./common/AttributeComparisonTable";
import { ErrorPanel } from "./common/ErrorPanel";
import { LoadingSkeleton } from "./common/LoadingSkeleton";

export function ReviewDetailView() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user, hasRole } = useAuth();

  // Action states
  const [actionLoading, setActionLoading] = useState(false);
  const [actionSuccess, setActionSuccess] = useState(null);
  const [actionError, setActionError] = useState(null);

  // Modals
  const [showApproveModal, setShowApproveModal] = useState(false);
  const [showRejectModal, setShowRejectModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [showSupersedeModal, setShowSupersedeModal] = useState(false);

  // Form fields
  const [approveNotes, setApproveNotes] = useState("Attribute consistency verified against GeM/IS standards.");
  const [rejectNotes, setRejectNotes] = useState("Specification mismatch with target canonical group.");
  const [editForm, setEditForm] = useState({
    targetGroupId: "",
    standardizedDescription: "",
    notes: "",
  });
  const [supersedeForm, setSupersedeForm] = useState({
    newDecision: "CONFIRMED",
    reason: "",
  });

  const { data: mapping, isLoading: loading, error: mappingError, refetch: loadMapping } = useQuery({
    queryKey: ["mapping", id],
    queryFn: () => api.getMappingById(id),
  });
  const error = mappingError?.message;

  const handleApprove = async () => {
    setActionLoading(true);
    setActionError(null);
    try {
      await api.approveMapping(id, approveNotes);
      setActionSuccess("Mapping confirmed. The group remains provisional until a senior reviewer publishes it.");
      setShowApproveModal(false);
      loadMapping();
    } catch (err) {
      setActionError(err.message || "Failed to approve mapping.");
    } finally {
      setActionLoading(false);
    }
  };

  const handleReject = async () => {
    setActionLoading(true);
    setActionError(null);
    try {
      await api.rejectMapping(id, rejectNotes);
      setActionSuccess("Mapping rejected. Raw material returned to staging unmatched pool.");
      setShowRejectModal(false);
      loadMapping();
    } catch (err) {
      setActionError(err.message || "Failed to reject mapping.");
    } finally {
      setActionLoading(false);
    }
  };

  const handleEdit = async (e) => {
    e.preventDefault();
    setActionLoading(true);
    setActionError(null);
    try {
      await api.editMapping(id, editForm);
      setActionSuccess("Mapping modified successfully.");
      setShowEditModal(false);
      loadMapping();
    } catch (err) {
      setActionError(err.message || "Failed to edit mapping.");
    } finally {
      setActionLoading(false);
    }
  };

  const handleSupersede = async (e) => {
    e.preventDefault();
    setActionLoading(true);
    setActionError(null);
    try {
      await api.supersedeMapping(id, supersedeForm.newDecision, supersedeForm.reason);
      setActionSuccess("Administrative override applied.");
      setShowSupersedeModal(false);
      loadMapping();
    } catch (err) {
      setActionError(err.message || "Failed to apply admin override.");
    } finally {
      setActionLoading(false);
    }
  };

  const handleFastTrack = async () => {
    setActionLoading(true);
    setActionError(null);
    try {
      const result = await api.approveAndPublish(id);
      setActionSuccess(`Published successfully as ${result.code}.`);
      await loadMapping();
    } catch (err) {
      setActionError(err.message || "Fast-track publication failed.");
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return (
      <div style={{ padding: "1.5rem" }}>
        <LoadingSkeleton count={4} />
      </div>
    );
  }

  if (error || !mapping) {
    return (
      <div style={{ padding: "1.5rem" }}>
        <ErrorPanel message={error || "Mapping not found"} onRetry={loadMapping} />
        <button className="btn btn-outline btn-sm" style={{ marginTop: "1rem" }} onClick={() => navigate("/review")}>
          <ArrowLeft size={14} /> Back to Review Queue
        </button>
      </div>
    );
  }

  // Conflict of Interest check: ordinary reviewers cannot decide their own CPSE's materials.
  const isOwnCpse = user?.cpse?.name && mapping.cpseName
    && user.cpse.name.toUpperCase() === mapping.cpseName.toUpperCase();
  const canApprove = hasRole(["REVIEWER", "SENIOR_REVIEWER"])
    && (!isOwnCpse || hasRole("SENIOR_REVIEWER"));

  // Parse explanation JSON
  let explanation = null;
  if (mapping.explanationJson) {
    try {
      explanation = typeof mapping.explanationJson === "string" ? JSON.parse(mapping.explanationJson) : mapping.explanationJson;
    } catch {
      explanation = null;
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "1.25rem" }}>
      {/* Top Navigation & Status */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "0.75rem" }}>
        <button className="btn btn-ghost btn-sm" onClick={() => navigate("/review")}>
          <ArrowLeft size={14} /> Back to Review Queue
        </button>
        <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
          <span className="badge badge-neutral" style={{ fontFamily: "var(--font-mono)" }}>
            Mapping #{mapping.mappingId || mapping.id}
          </span>
          <ConfidenceTierBadge score={mapping.confidenceScore || 0} tier={mapping.confidenceTier} />
          {mapping.status === "CONFIRMED" && <span className="badge badge-success"><Check size={12} /> Confirmed</span>}
          {mapping.status === "REJECTED" && <span className="badge badge-danger"><XCircle size={12} /> Rejected</span>}
          {mapping.status === "PENDING" && <span className="badge badge-warning"><Clock size={12} /> Pending Review</span>}
          {mapping.status === "SUPERSEDED" && <span className="badge badge-warning"><ShieldAlert size={12} /> Superseded</span>}
        </div>
      </div>

      {/* Action Banners */}
      {actionSuccess && (
        <div style={{ background: "var(--success-bg)", border: "1px solid var(--success-border)", color: "var(--success)", padding: "0.75rem 1rem", borderRadius: "var(--radius-sm)", display: "flex", alignItems: "center", gap: "0.5rem" }}>
          <CheckCircle2 size={16} />
          <span>{actionSuccess}</span>
        </div>
      )}
      {actionError && (
        <div style={{ background: "var(--danger-bg)", border: "1px solid var(--danger-border)", color: "var(--danger)", padding: "0.75rem 1rem", borderRadius: "var(--radius-sm)", display: "flex", alignItems: "center", gap: "0.5rem" }}>
          <AlertTriangle size={16} />
          <span>{actionError}</span>
        </div>
      )}

      <div className="card" style={{ padding: "0.9rem 1.1rem" }}>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "0.8rem" }}>
          <div><div className="text-xs text-muted">Routing decision</div><strong>{mapping.routingDecision || "Legacy review"}</strong></div>
          <div><div className="text-xs text-muted">Decision source</div><strong>{mapping.decisionSource || "Legacy"}</strong></div>
          <div><div className="text-xs text-muted">Candidate margin</div><strong>{mapping.candidateMargin != null ? `${(Number(mapping.candidateMargin) * 100).toFixed(1)}%` : "Not recorded"}</strong></div>
          <div><div className="text-xs text-muted">Model version</div><strong>{mapping.modelVersion || "Not recorded"}</strong></div>
        </div>
        {mapping.scoreBreakdown && (
          <div style={{ marginTop: "0.75rem", display: "flex", flexWrap: "wrap", gap: "0.45rem" }}>
            {Object.entries(mapping.scoreBreakdown).map(([key, value]) => (
              <span className="badge badge-neutral" key={key}>{key}: {(Number(value) * 100).toFixed(1)}%</span>
            ))}
          </div>
        )}
      </div>

      {/* Four-Eyes Conflict of Interest Alert */}
      {isOwnCpse && hasRole("REVIEWER") && (
        <div style={{ display: "flex", gap: "0.75rem", background: "var(--warning-bg)", border: "1px solid var(--warning-border)", borderRadius: "var(--radius-md)", padding: "0.85rem 1.15rem" }}>
          <ShieldAlert size={16} color="var(--warning)" style={{ flexShrink: 0, marginTop: "2px" }} />
          <div style={{ fontSize: "0.825rem", color: "var(--warning-text)" }}>
            <strong>Four-Eyes Governance Policy:</strong> You belong to <strong>{user.cpse.name}</strong>, which submitted this item. To prevent self-approvals, a reviewer from another CPSE or a senior reviewer must validate this record.
          </div>
        </div>
      )}

      {/* Main Side-by-Side Comparison Grid */}
      <div className="review-comparison-grid">
        {/* Left: Raw Ingested CPSE Item */}
        <div className="card">
          <div className="card-header">
            <div className="card-title">
              <Building2 size={16} color="var(--warning)" />
              Submitted material — {mapping.cpseName}
            </div>
          </div>
          <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "0.85rem" }}>
            <div>
              <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase", fontWeight: 700 }}>Legacy Plant Code</div>
              <div style={{ fontFamily: "var(--font-mono)", fontSize: "0.95rem", fontWeight: 700, color: "var(--text-primary)", marginTop: "2px" }}>
                {mapping.cpseMaterialCode || mapping.plantCode || "—"}
              </div>
            </div>
            <div>
              <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase", fontWeight: 700 }}>Raw Description</div>
              <div style={{ fontSize: "0.9rem", fontWeight: 500, color: "var(--text-primary)", marginTop: "2px", lineHeight: 1.4 }}>
                {mapping.materialDescription || mapping.rawDescription}
              </div>
            </div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.6rem" }}>
              <div style={{ background: "var(--bg-subtle)", padding: "0.5rem", borderRadius: "var(--radius-xs)" }}>
                <div style={{ fontSize: "0.68rem", color: "var(--text-dim)" }}>Legacy Category</div>
                <div style={{ fontWeight: 600, fontSize: "0.8rem", marginTop: "2px" }}>{mapping.rawCategory || "Not recorded"}</div>
              </div>
              <div style={{ background: "var(--bg-subtle)", padding: "0.5rem", borderRadius: "var(--radius-xs)" }}>
                <div style={{ fontSize: "0.68rem", color: "var(--text-dim)" }}>Legacy Standard / Spec</div>
                <div style={{ fontWeight: 600, fontSize: "0.8rem", marginTop: "2px" }}>{mapping.rawSpecification || "—"}</div>
              </div>
            </div>
          </div>
        </div>

        {/* Right: Target Canonical NUMM Record */}
        <div className="card">
          <div className="card-header">
            <div className="card-title">
              <Layers size={16} color="var(--accent)" />
              Proposed catalog record
            </div>
          </div>
          <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "0.85rem" }}>
            <div>
              <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase", fontWeight: 700 }}>National Unified Code</div>
              <div style={{ marginTop: "4px" }}>
                {mapping.commonMaterialCode ? (
                  <CodeChip code={mapping.commonMaterialCode} size="lg" />
                ) : (
                  <span style={{ fontSize: "0.8rem", color: "var(--text-dim)", fontStyle: "italic" }}>
                    Provisional / Will be minted upon approval
                  </span>
                )}
              </div>
            </div>
            <div>
              <div style={{ fontSize: "0.7rem", color: "var(--text-muted)", textTransform: "uppercase", fontWeight: 700 }}>Canonical Standard Description</div>
              <div style={{ fontSize: "0.9rem", fontWeight: 600, color: "var(--text-primary)", marginTop: "2px", lineHeight: 1.4 }}>
                {mapping.standardizedDescription || mapping.groupCanonicalName}
              </div>
            </div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.6rem" }}>
              <div style={{ background: "var(--bg-subtle)", padding: "0.5rem", borderRadius: "var(--radius-xs)" }}>
                <div style={{ fontSize: "0.68rem", color: "var(--text-dim)" }}>UNSPSC Commodity</div>
                <div style={{ fontWeight: 600, fontSize: "0.8rem", marginTop: "2px" }}>{mapping.categoryName || "Not recorded"}</div>
              </div>
              <div style={{ background: "var(--bg-subtle)", padding: "0.5rem", borderRadius: "var(--radius-xs)" }}>
                <div style={{ fontSize: "0.68rem", color: "var(--text-dim)" }}>Relationship Type</div>
                <div style={{ fontWeight: 600, fontSize: "0.8rem", marginTop: "2px" }}>{mapping.relationshipType || "Not recorded"}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Attribute Comparison Centerpiece */}
      <div className="card">
        <div className="card-header">
          <div className="card-title">
            <Cpu size={16} color="var(--accent)" />
            Technical Attribute Equivalence Matrix
          </div>
        </div>
        <div className="card-body">
          <AttributeComparisonTable
            leftAttributes={mapping.rawAttributes || {}}
            rightAttributes={mapping.canonicalAttributes || {}}
            leftLabel={mapping.cpseName}
            rightLabel="National Standard"
          />
        </div>
      </div>

      {/* Explainability Checks */}
      {explanation && (
        <div className="card">
          <div className="card-header">
            <div className="card-title">
              <Cpu size={16} color="var(--accent)" />
              Match Rationale & Diagnostic Breakdown
            </div>
          </div>
          <div className="card-body" style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1rem" }}>
            <div style={{ background: "var(--success-bg)", border: "1px solid var(--success-border)", borderRadius: "var(--radius-sm)", padding: "0.85rem" }}>
              <div style={{ fontSize: "0.8rem", fontWeight: 700, color: "var(--success)", display: "flex", alignItems: "center", gap: "0.4rem", marginBottom: "0.4rem" }}>
                <CheckCircle2 size={14} /> Verified Equivalent Attributes ({explanation.checks?.length || 0})
              </div>
              {explanation.checks?.map((c, i) => (
                <div key={i} style={{ fontSize: "0.78rem", color: "var(--text-secondary)", padding: "0.15rem 0", display: "flex", gap: "0.4rem" }}>
                  <Check size={12} color="var(--success)" style={{ flexShrink: 0, marginTop: "2px" }} />
                  <span>{c}</span>
                </div>
              ))}
              {!explanation.checks?.length && <div style={{ fontSize: "0.75rem", color: "var(--text-dim)", fontStyle: "italic" }}>No explicit check notes.</div>}
            </div>

            <div style={{ background: "var(--warning-bg)", border: "1px solid var(--warning-border)", borderRadius: "var(--radius-sm)", padding: "0.85rem" }}>
              <div style={{ fontSize: "0.8rem", fontWeight: 700, color: "var(--warning)", display: "flex", alignItems: "center", gap: "0.4rem", marginBottom: "0.4rem" }}>
                <AlertTriangle size={14} /> Discrepancies & Divergences ({(explanation.warnings?.length || 0) + (explanation.conflicts?.length || 0)})
              </div>
              {explanation.warnings?.map((w, i) => (
                <div key={i} style={{ fontSize: "0.78rem", color: "var(--warning)", padding: "0.15rem 0" }}>• {w}</div>
              ))}
              {explanation.conflicts?.map((c, i) => (
                <div key={i} style={{ fontSize: "0.78rem", color: "var(--danger)", padding: "0.15rem 0", fontWeight: 600 }}>• {c}</div>
              ))}
              {!explanation.warnings?.length && !explanation.conflicts?.length && (
                <div style={{ fontSize: "0.75rem", color: "var(--text-dim)", fontStyle: "italic" }}>No discrepancies identified.</div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Decision Action Bar */}
      <div className="card" style={{ padding: "1.25rem", display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "1rem" }}>
        <div>
          <div style={{ fontSize: "0.85rem", fontWeight: 700, color: "var(--text-primary)" }}>Governance Decisions</div>
          <div style={{ fontSize: "0.75rem", color: "var(--text-muted)" }}>
            Decisions are recorded in the material audit trail.
          </div>
        </div>

        <div style={{ display: "flex", gap: "0.6rem", flexWrap: "wrap" }}>
          {hasRole("ADMIN") && (
            <button className="btn btn-outline btn-sm" onClick={() => setShowSupersedeModal(true)}>
              <ShieldAlert size={14} /> Admin Supersede
            </button>
          )}

          {hasRole(["REVIEWER", "SENIOR_REVIEWER"]) && mapping.status === "PENDING" && (
            <button className="btn btn-outline btn-sm" onClick={() => {
              setEditForm({
                targetGroupId: mapping.targetGroupId || mapping.materialGroupId || "",
                standardizedDescription: mapping.standardizedDescription || "",
                notes: "",
              });
              setShowEditModal(true);
            }}>
              <Edit3 size={14} /> Edit Mapping
            </button>
          )}

          {canApprove && mapping.status === "PENDING" && (
            <>
              <button className="btn btn-danger btn-sm" onClick={() => setShowRejectModal(true)}>
                <XCircle size={14} /> Reject
              </button>
              <button className="btn btn-success btn-sm" onClick={() => setShowApproveModal(true)}>
                <CheckCircle2 size={14} /> Confirm Mapping
              </button>
            </>
          )}
          {hasRole(["SENIOR_REVIEWER", "ADMIN"])
            && (mapping.status === "CONFIRMED" || (mapping.status === "PENDING" && hasRole("ADMIN")))
            && mapping.confidenceTier === "HIGH"
            && Number(mapping.confidenceScore || 0) >= 0.85 && (
            <button className="btn btn-primary btn-sm" onClick={handleFastTrack} disabled={actionLoading}>
              <CheckCircle2 size={14} /> Approve &amp; Publish
            </button>
          )}
        </div>
      </div>

      {/* Approve Modal */}
      {showApproveModal && (
        <div style={{ position: "fixed", inset: 0, background: "var(--overlay)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000, padding: "1rem" }}>
          <div className="card" style={{ width: "100%", maxWidth: "480px" }}>
            <div className="card-header">
              <div className="card-title"><CheckCircle2 size={16} color="var(--success)" /> Confirm Governance Approval</div>
            </div>
            <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "0.85rem" }}>
              <p style={{ fontSize: "0.82rem", color: "var(--text-secondary)" }}>
                Approving this mapping confirms the material-to-group relationship. A separate authorized reviewer can then publish its authoritative <strong>NUMM-CCCCCC-MM-DDD-RRR-NNNNNN-K</strong> code with an ISO 7064 MOD 37,36 check character.
              </p>
              <div>
                <label className="form-label" style={{ fontSize: "0.75rem" }}>Statutory Governance Notes *</label>
                <textarea
                  className="form-input"
                  rows={3}
                  value={approveNotes}
                  onChange={(e) => setApproveNotes(e.target.value)}
                  placeholder="State technical basis for approval..."
                />
              </div>
            </div>
            <div className="card-footer" style={{ display: "flex", justifyContent: "flex-end", gap: "0.6rem", padding: "0.75rem 1.25rem", borderTop: "1px solid var(--border)" }}>
              <button className="btn btn-outline btn-sm" onClick={() => setShowApproveModal(false)} disabled={actionLoading}>Cancel</button>
              <button className="btn btn-success btn-sm" onClick={handleApprove} disabled={actionLoading}>
                {actionLoading ? "Recording..." : "Confirm Approval"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Reject Modal */}
      {showRejectModal && (
        <div style={{ position: "fixed", inset: 0, background: "var(--overlay)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000, padding: "1rem" }}>
          <div className="card" style={{ width: "100%", maxWidth: "480px" }}>
            <div className="card-header">
              <div className="card-title"><XCircle size={16} color="var(--danger)" /> Reject Proposed Mapping</div>
            </div>
            <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "0.85rem" }}>
              <p style={{ fontSize: "0.82rem", color: "var(--text-secondary)" }}>
                Rejecting this mapping will detach the raw plant item from this candidate group and return it to the unmatched staging pool.
              </p>
              <div>
                <label className="form-label" style={{ fontSize: "0.75rem" }}>Rejection Justification *</label>
                <textarea
                  className="form-input"
                  rows={3}
                  value={rejectNotes}
                  onChange={(e) => setRejectNotes(e.target.value)}
                  placeholder="Specify divergence (e.g. pressure rating mismatch, alloy grade conflict)..."
                />
              </div>
            </div>
            <div className="card-footer" style={{ display: "flex", justifyContent: "flex-end", gap: "0.6rem", padding: "0.75rem 1.25rem", borderTop: "1px solid var(--border)" }}>
              <button className="btn btn-outline btn-sm" onClick={() => setShowRejectModal(false)} disabled={actionLoading}>Cancel</button>
              <button className="btn btn-danger btn-sm" onClick={handleReject} disabled={actionLoading}>
                {actionLoading ? "Recording..." : "Confirm Rejection"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Edit Modal */}
      {showEditModal && (
        <div style={{ position: "fixed", inset: 0, background: "var(--overlay)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000, padding: "1rem" }}>
          <div className="card" style={{ width: "100%", maxWidth: "500px" }}>
            <div className="card-header">
              <div className="card-title"><Edit3 size={16} color="var(--accent)" /> Modify Mapping Classification</div>
            </div>
            <form onSubmit={handleEdit}>
              <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "0.85rem" }}>
                <div>
                  <label className="form-label" style={{ fontSize: "0.75rem" }}>Target Canonical Group ID *</label>
                  <input
                    type="number"
                    required
                    className="form-input"
                    value={editForm.targetGroupId}
                    onChange={(e) => setEditForm({ ...editForm, targetGroupId: e.target.value })}
                  />
                </div>
                <div>
                  <label className="form-label" style={{ fontSize: "0.75rem" }}>Standardized Description</label>
                  <input
                    type="text"
                    className="form-input"
                    value={editForm.standardizedDescription}
                    onChange={(e) => setEditForm({ ...editForm, standardizedDescription: e.target.value })}
                  />
                </div>
                <div>
                  <label className="form-label" style={{ fontSize: "0.75rem" }}>Modification Justification *</label>
                  <textarea
                    required
                    className="form-input"
                    rows={2}
                    value={editForm.notes}
                    onChange={(e) => setEditForm({ ...editForm, notes: e.target.value })}
                    placeholder="Reason for reclassification..."
                  />
                </div>
              </div>
              <div className="card-footer" style={{ display: "flex", justifyContent: "flex-end", gap: "0.6rem", padding: "0.75rem 1.25rem", borderTop: "1px solid var(--border)" }}>
                <button type="button" className="btn btn-outline btn-sm" onClick={() => setShowEditModal(false)} disabled={actionLoading}>Cancel</button>
                <button type="submit" className="btn btn-primary btn-sm" disabled={actionLoading}>Save Changes</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Admin Supersede Modal */}
      {showSupersedeModal && (
        <div style={{ position: "fixed", inset: 0, background: "var(--overlay)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000, padding: "1rem" }}>
          <div className="card" style={{ width: "100%", maxWidth: "500px" }}>
            <div className="card-header">
              <div className="card-title"><ShieldAlert size={16} color="var(--danger)" /> Statutory Administrative Override</div>
            </div>
            <form onSubmit={handleSupersede}>
              <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "0.85rem" }}>
                <div style={{ background: "var(--danger-bg)", border: "1px solid var(--danger-border)", padding: "0.75rem", borderRadius: "var(--radius-sm)", fontSize: "0.8rem", color: "var(--danger)" }}>
                  Admin overrides supersede previous reviewer decisions and generate a statutory audit flag.
                </div>
                <div>
                  <label className="form-label" style={{ fontSize: "0.75rem" }}>Override Decision *</label>
                  <select
                    className="form-select"
                    value={supersedeForm.newDecision}
                    onChange={(e) => setSupersedeForm({ ...supersedeForm, newDecision: e.target.value })}
                  >
                    <option value="CONFIRMED">CONFIRMED (Approve Mapping)</option>
                    <option value="REJECTED">REJECTED (Reject Mapping)</option>
                  </select>
                </div>
                <div>
                  <label className="form-label" style={{ fontSize: "0.75rem" }}>Statutory Justification Reason *</label>
                  <textarea
                    required
                    className="form-input"
                    rows={3}
                    value={supersedeForm.reason}
                    onChange={(e) => setSupersedeForm({ ...supersedeForm, reason: e.target.value })}
                    placeholder="Document regulatory compliance or standardization rationale..."
                  />
                </div>
              </div>
              <div className="card-footer" style={{ display: "flex", justifyContent: "flex-end", gap: "0.6rem", padding: "0.75rem 1.25rem", borderTop: "1px solid var(--border)" }}>
                <button type="button" className="btn btn-outline btn-sm" onClick={() => setShowSupersedeModal(false)} disabled={actionLoading}>Cancel</button>
                <button type="submit" className="btn btn-danger btn-sm" disabled={actionLoading}>Apply Override</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
