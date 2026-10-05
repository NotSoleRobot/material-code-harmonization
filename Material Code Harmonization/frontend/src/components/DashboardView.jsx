import React, { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../services/api";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import {
  Layers,
  TrendingUp,
  Building2,
  HelpCircle,
  Play,
  CheckCircle2,
  AlertCircle,
  Tag,
  ClipboardCheck,
} from "lucide-react";
import { LoadingSkeleton } from "./common/LoadingSkeleton";
import { ErrorPanel } from "./common/ErrorPanel";

export function DashboardView() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [actionMessage, setActionMessage] = useState(null);
  const [activeJobId, setActiveJobId] = useState(null);

  const {
    data: stats,
    isLoading: statsLoading,
    error: statsError,
    refetch: refetchStats,
  } = useQuery({
    queryKey: ["dashboardStats"],
    queryFn: api.getDashboardStats,
  });

  const batchHarmonizeMutation = useMutation({
    mutationFn: api.harmonizeAll,
    onSuccess: (data) => {
      // Backend returns 202 Accepted with { jobId, status, totalItems, message }
      const totalItems = data?.totalItems ?? 0;
      const jobId = data?.jobId;
      setActiveJobId(jobId || null);
      setActionMessage({
        type: "success",
        text: jobId
          ? `Harmonization job #${jobId} started — ${totalItems} unmatched material(s) queued for processing.`
          : `Batch harmonization triggered. ${totalItems} item(s) queued.`,
      });
    },
    onError: (err) => {
      setActionMessage({
        type: "error",
        text: `Batch harmonization failed: ${err.message}`,
      });
    },
  });

  const { data: activeJob } = useQuery({
    queryKey: ["harmonizationJob", activeJobId],
    queryFn: () => api.getJobStatus(activeJobId),
    enabled: Boolean(activeJobId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === "COMPLETED" || status === "FAILED" ? false : 1500;
    },
  });

  const jobStatus = activeJob?.status;
  const processedItems = activeJob?.processedItems;
  const totalItems = activeJob?.totalItems;
  const errorMessage = activeJob?.errorMessage;

  React.useEffect(() => {
    if (!jobStatus || !["COMPLETED", "FAILED"].includes(jobStatus)) return;
    // Polling is external state; reflect the terminal transition in the action banner.
    // oxlint-disable-next-line react/set-state-in-effect
    setActionMessage({
      type: jobStatus === "COMPLETED" ? "success" : "error",
      text: jobStatus === "COMPLETED"
        ? `Harmonization completed: ${processedItems}/${totalItems} materials processed.`
        : `Harmonization failed after ${processedItems}/${totalItems}: ${errorMessage || "See job details."}`,
    });
    queryClient.invalidateQueries({ queryKey: ["dashboardStats"] });
    queryClient.invalidateQueries({ queryKey: ["mappings"] });
    setActiveJobId(null);
  }, [jobStatus, processedItems, totalItems, errorMessage, queryClient]);

  if (statsLoading) {
    return (
      <div className="page-container">
        <LoadingSkeleton rows={6} type="card" />
      </div>
    );
  }

  if (statsError) {
    return (
      <div className="page-container">
        <ErrorPanel error={statsError} onRetry={refetchStats} title="Failed to load dashboard statistics" />
      </div>
    );
  }

  return (
    <div className="page-container">
      {/* Page Header */}
      <div className="page-header">
        <div>
          <h1 className="page-title">{t("dashboard.title")}</h1>
          <p className="page-subtitle">{t("dashboard.subtitle")}</p>
        </div>
        <div className="page-actions">
          <button
            className="btn btn-primary"
            onClick={() => batchHarmonizeMutation.mutate()}
            disabled={batchHarmonizeMutation.isPending}
            id="btn-batch-harmonize"
          >
            <Play size={14} aria-hidden="true" />
            {batchHarmonizeMutation.isPending ? "Harmonizing Inventory…" : t("dashboard.batchHarmonizeBtn")}
          </button>
        </div>
      </div>

      {actionMessage && (
        <div className={`alert-banner alert-${actionMessage.type === "success" ? "success" : "danger"} mb-4`} role="status">
          {actionMessage.type === "success" ? <CheckCircle2 size={16} /> : <AlertCircle size={16} />}
          <span>{actionMessage.text}</span>
        </div>
      )}
      {activeJobId && activeJob && (
        <div className="card mb-4" style={{ padding: "1rem" }}>
          <div style={{ display: "flex", justifyContent: "space-between", marginBottom: "0.5rem" }}>
            <strong>Harmonization job #{activeJobId}</strong>
            <span>{activeJob.processedItems || 0} / {activeJob.totalItems || 0}</span>
          </div>
          <progress style={{ width: "100%" }} value={activeJob.processedItems || 0} max={Math.max(activeJob.totalItems || 1, 1)} />
        </div>
      )}

      {/* KPI Stats Grid */}
      <div className="kpi-grid">
        {/* Total Ingested */}
        <div className="kpi-card">
          <div className="kpi-icon-wrapper kpi-accent">
            <Layers size={16} aria-hidden="true" />
          </div>
          <div className="kpi-content">
            <span className="kpi-label">{t("dashboard.totalMaterials")}</span>
            <span className="kpi-value">{stats?.totalMaterials?.toLocaleString() ?? 0}</span>
            <span className="kpi-sub text-muted">Across {stats?.totalCpses ?? 0} CPSEs</span>
          </div>
        </div>

        {/* Harmonized catalog groups */}
        <div className="kpi-card">
          <div className="kpi-icon-wrapper kpi-success">
            <Tag size={16} aria-hidden="true" />
          </div>
          <div className="kpi-content">
            <span className="kpi-label">{t("dashboard.totalGroups")}</span>
            <span className="kpi-value">{stats?.totalGroups?.toLocaleString() ?? 0}</span>
            <span className="kpi-sub text-success">Active Harmonized Records</span>
          </div>
        </div>

        {/* Deduplication Rate */}
        <div className="kpi-card">
          <div className="kpi-icon-wrapper kpi-info">
            <TrendingUp size={16} aria-hidden="true" />
          </div>
          <div className="kpi-content">
            <div style={{ display: "flex", alignItems: "center", gap: "4px" }}>
              <span className="kpi-label">{t("dashboard.dedupRate")}</span>
              <span className="info-tooltip" title={t("dashboard.dedupFormula")}>
                <HelpCircle size={12} aria-hidden="true" />
              </span>
            </div>
            <span className="kpi-value">{stats?.deduplicationRate ?? 0}%</span>
            <span className="kpi-sub text-muted">Reviewed duplicate reduction</span>
          </div>
        </div>

        {/* Pending review */}
        <div className="kpi-card">
          <div className="kpi-icon-wrapper kpi-warning">
            <ClipboardCheck size={16} aria-hidden="true" />
          </div>
          <div className="kpi-content">
            <span className="kpi-label">{t("dashboard.pendingReview")}</span>
            <span className="kpi-value">{stats?.pendingReviewCount?.toLocaleString() ?? 0}</span>
            <span className="kpi-sub text-muted">Awaiting technical decision</span>
          </div>
        </div>
      </div>

      {/* Governance & Pool Status Bar */}
      <div className="card mb-4 p-3">
        <div className="gov-status-row">
          <div className="gov-status-item">
            <span className="gov-dot dot-warning" />
            <span className="gov-label">{t("dashboard.pendingReview")}:</span>
            <span className="gov-count font-bold text-warning">{stats?.pendingReviewCount ?? 0}</span>
            <Link to="/review" className="text-xs ml-1 text-accent hover:underline">
              (View Queue)
            </Link>
          </div>
          <div className="gov-status-divider" />
          <div className="gov-status-item">
            <span className="gov-dot dot-success" />
            <span className="gov-label">{t("dashboard.confirmedMappings")}:</span>
            <span className="gov-count font-bold text-success">{stats?.confirmedCount ?? 0}</span>
          </div>
          <div className="gov-status-divider" />
          <div className="gov-status-item">
            <span className="gov-dot dot-danger" />
            <span className="gov-label">{t("dashboard.rejectedMappings")}:</span>
            <span className="gov-count font-bold text-danger">{stats?.rejectedCount ?? 0}</span>
          </div>
          <div className="gov-status-divider" />
          <div className="gov-status-item">
            <span className="gov-dot dot-muted" />
            <span className="gov-label">{t("dashboard.unmatchedItems")}:</span>
            <span className="gov-count font-bold">{stats?.unmatchedCount ?? 0}</span>
          </div>
        </div>
      </div>

      {/* CPSE Breakdown & Category Distribution */}
      <div className="dashboard-two-col">
        {/* CPSE Participation Table */}
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">
              <Building2 size={16} aria-hidden="true" /> CPSE Master Participation
            </h2>
          </div>
          <div className="table-responsive">
            <table className="data-table" aria-label="CPSE Participation Table">
              <caption>CPSE material-ingestion and harmonization coverage</caption>
              <thead>
                <tr>
                  <th scope="col">CPSE Enterprise</th>
                  <th scope="col" className="text-right">Total Ingested</th>
                  <th scope="col" className="text-right">Harmonized</th>
                  <th scope="col" className="text-right">Coverage</th>
                </tr>
              </thead>
              <tbody>
                {stats?.cpseBreakdown && stats.cpseBreakdown.length > 0 ? (
                  stats.cpseBreakdown.map((c, i) => {
                    const pct = c.materialCount > 0 ? Math.round((c.mappedCount / c.materialCount) * 100) : 0;
                    return (
                      <tr key={i}>
                        <td className="font-medium">{c.cpseName}</td>
                        <td className="text-right">{c.materialCount}</td>
                        <td className="text-right text-success font-semibold">{c.mappedCount}</td>
                        <td className="text-right">
                          <div className="progress-bar-cell">
                            <span className="text-xs">{pct}%</span>
                            <div className="progress-track">
                              <div className="progress-fill" style={{ width: `${pct}%` }} />
                            </div>
                          </div>
                        </td>
                      </tr>
                    );
                  })
                ) : (
                  <tr>
                    <td colSpan={4} className="text-center text-muted p-3">
                      No CPSE records ingested yet.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Category Breakdown */}
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">
              <Tag size={16} aria-hidden="true" /> Commodity Classification
            </h2>
          </div>
          <div className="category-chips-grid p-3">
            {stats?.categoryDistribution && Object.keys(stats.categoryDistribution).length > 0 ? (
              Object.entries(stats.categoryDistribution).map(([cat, count]) => (
                <div key={cat} className="category-stat-badge">
                  <span className="cat-name">{cat}</span>
                  <span className="cat-count">{count}</span>
                </div>
              ))
            ) : (
              <span className="text-muted text-sm">No classified items yet.</span>
            )}
          </div>
        </div>
      </div>

    </div>
  );
}
