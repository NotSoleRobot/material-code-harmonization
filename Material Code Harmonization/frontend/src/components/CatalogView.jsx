import React, { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams, Link } from "react-router-dom";
import { api } from "../services/api";
import { useAuth } from "../context/useAuth";
import { useTranslation } from "react-i18next";
import {
  Search,
  Download,
  PlusCircle,
  Eye,
  BookOpen,
  FileSpreadsheet,
} from "lucide-react";
import { CodeChip } from "./common/CodeChip";
import { LoadingSkeleton } from "./common/LoadingSkeleton";
import { ErrorPanel } from "./common/ErrorPanel";
import { Table } from "./common/Table";

export function CatalogView() {
  const { user } = useAuth();
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();

  const query = searchParams.get("q") || "";
  const selectedCategory = searchParams.get("category") || "ALL";
  const viewMode = searchParams.get("view") || "canonical"; // 'canonical' or 'crossref'
  const selectedStatus = "ALL";
  const page = Math.max(0, Number.parseInt(searchParams.get("page") || "0", 10) || 0);
  const pageSize = [20, 50, 100].includes(Number(searchParams.get("size")))
    ? Number(searchParams.get("size")) : 20;

  const [showExportModal, setShowExportModal] = useState(false);
  const [exportFormat, setExportFormat] = useState("csv");
  const [exportError, setExportError] = useState(null);
  const [isExporting, setIsExporting] = useState(false);

  const isOperator = user?.role === "OPERATOR";
  const canExport = ["SENIOR_REVIEWER", "ADMIN"].includes(user?.role);

  // Fetch canonical codes
  const {
    data: searchResults,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ["codesSearch", query, selectedStatus, selectedCategory, page, pageSize],
    queryFn: () => api.searchCodes(query, selectedStatus, selectedCategory, page, pageSize),
    placeholderData: (previousData) => previousData,
  });

  const { data: catalogCategories = [] } = useQuery({
    queryKey: ["catalogCategories"],
    queryFn: () => api.getCatalogCategories(),
  });

  const handleSearchChange = (e) => {
    const val = e.target.value;
    const newParams = new URLSearchParams(searchParams);
    if (val) {
      newParams.set("q", val);
    } else {
      newParams.delete("q");
    }
    newParams.delete("page");
    setSearchParams(newParams);
  };

  const handleCategoryFilter = (cat) => {
    const newParams = new URLSearchParams(searchParams);
    if (cat === "ALL") {
      newParams.delete("category");
    } else {
      newParams.set("category", cat);
    }
    newParams.delete("page");
    setSearchParams(newParams);
  };

  const handleViewModeToggle = (mode) => {
    const newParams = new URLSearchParams(searchParams);
    newParams.set("view", mode);
    setSearchParams(newParams);
  };

  const handlePageChange = (nextPage) => {
    const newParams = new URLSearchParams(searchParams);
    if (nextPage <= 0) newParams.delete("page");
    else newParams.set("page", String(nextPage));
    setSearchParams(newParams);
  };

  const handlePageSizeChange = (nextSize) => {
    const newParams = new URLSearchParams(searchParams);
    newParams.set("size", String(nextSize));
    newParams.delete("page");
    setSearchParams(newParams);
  };

  const pageResults = searchResults?.content || [];
  const filteredResults = pageResults;

  const handleDownloadExport = async (type) => {
    setExportError(null);
    setIsExporting(true);
    try {
      await api.downloadExport(type, exportFormat);
      setShowExportModal(false);
    } catch (err) {
      setExportError(err.message || "Export download failed. Please check network connectivity or role permissions.");
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <div className="page-container">
      {/* Page Header */}
      <div className="page-header">
        <div>
          <h1 className="page-title">{isOperator ? t("catalog.searchTitle") : t("catalog.unifiedTitle")}</h1>
          <p className="page-subtitle">{isOperator ? t("catalog.searchSub") : t("catalog.unifiedSub")}</p>
        </div>
        <div className="page-actions">
          {isOperator ? (
            <Link to="/ingest" className="btn btn-primary" id="btn-submit-material">
              <PlusCircle size={15} aria-hidden="true" /> {t("catalog.requestCodeBtn")}
            </Link>
          ) : canExport ? (
            <button className="btn btn-outline" onClick={() => setShowExportModal(true)} id="btn-export-catalog">
              <Download size={15} aria-hidden="true" /> {t("catalog.exportBtn")}
            </button>
          ) : null}
        </div>
      </div>

      {/* Search & Filter Toolbar (URL Synchronized) */}
      <div className="card filter-card mb-4 p-3">
        <div className="filter-bar-grid">
          <div className="search-input-wrapper">
            <Search size={16} className="search-icon" aria-hidden="true" />
            <input
              type="text"
              className="form-input search-input"
              placeholder={t("catalog.searchPlaceholder")}
              value={query}
              onChange={handleSearchChange}
              id="catalog-search-input"
            />
          </div>

          <div className="filter-options-row">
            <div className="select-wrapper">
              <select
                className="form-select"
                value={selectedCategory}
                onChange={(e) => handleCategoryFilter(e.target.value)}
                aria-label="Filter by Material Commodity Class"
              >
                <option value="ALL">{t("catalog.allCategories")}</option>
                {catalogCategories.map((category) => (
                  <option key={category} value={category}>{category}</option>
                ))}
              </select>
            </div>

            <>
                {/* View mode toggle */}
                <div className="btn-group">
                  <button
                    className={`btn btn-sm ${viewMode === "canonical" ? "btn-primary" : "btn-outline"}`}
                    onClick={() => handleViewModeToggle("canonical")}
                  >
                    <BookOpen size={13} aria-hidden="true" /> Materials
                  </button>
                  <button
                    className={`btn btn-sm ${viewMode === "crossref" ? "btn-primary" : "btn-outline"}`}
                    onClick={() => handleViewModeToggle("crossref")}
                  >
                    <FileSpreadsheet size={13} aria-hidden="true" /> Source records
                  </button>
                </div>
            </>
          </div>
        </div>
      </div>

      {/* Main Content Area */}
      {isLoading ? (
        <LoadingSkeleton rows={6} type="table" />
      ) : error ? (
        <ErrorPanel error={error} onRetry={refetch} title="Failed to search national master catalog" />
      ) : filteredResults.length === 0 ? (
        <div className="card text-center p-5">
          <p className="text-lg font-medium text-muted mb-3">{t("catalog.emptySearch")}</p>
          {isOperator && (
            <div>
              <p className="text-sm text-dim mb-4">
                Does your required material not exist in the catalog? Submit it for harmonization.
              </p>
              <Link to="/ingest" className="btn btn-primary">
                <PlusCircle size={15} /> {t("catalog.requestCodeBtn")}
              </Link>
            </div>
          )}
        </div>
      ) : viewMode === "canonical" ? (
        /* Canonical Groups Table */
        <div className="card">
            <Table caption="Canonical material master">
              <thead>
                <tr>
                  <th scope="col" style={{ width: "22%" }}>National Material Code</th>
                  <th scope="col" style={{ width: "38%" }}>Standardized Description</th>
                  <th scope="col" style={{ width: "15%" }}>Commodity Class</th>
                  <th scope="col" style={{ width: "8%" }}>UOM</th>
                  <th scope="col" style={{ width: "10%" }} className="text-center">Status</th>
                  <th scope="col" style={{ width: "7%" }} className="text-center">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredResults.map((group) => {
                  const code = group.commonMaterialCode;
                  return (
                    <tr key={group.commonMaterialCode}>
                      <td>
                        <CodeChip
                          code={code}
                          size="md"
                          categoryPath={group.categoryPath}
                        />
                      </td>
                      <td>
                        <div className="font-medium text-primary">{group.standardizedDescription}</div>
                        {group.standardizedSpecification && (
                          <div className="text-xs text-muted mt-0.5">{group.standardizedSpecification}</div>
                        )}
                      </td>
                      <td>
                        <span className="badge badge-neutral">{group.categoryName || "GENERAL"}</span>
                      </td>
                      <td>{group.standardizedUom || "NOS"}</td>
                      <td className="text-center">
                        <span className={`badge ${group.status === "HARMONIZED" ? "badge-success" : "badge-warning"}`}>
                          {group.status === "HARMONIZED" ? "Harmonized" : "Needs review"}
                        </span>
                      </td>
                      <td className="text-center">
                        <Link
                          to={`/catalog/${encodeURIComponent(code)}`}
                          className="btn btn-ghost btn-sm btn-icon"
                          title={t("catalog.viewDetails")}
                          aria-label={`View details for ${code}`}
                        >
                          <Eye size={15} />
                        </Link>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </Table>
        </div>
      ) : (
        /* Cross-Reference Index View (Reviewer / Admin) */
        <div className="card">
            <Table caption="Cross-reference index">
              <thead>
                <tr>
                  <th scope="col">CPSE</th>
                  <th scope="col">Plant Material Code</th>
                  <th scope="col">Raw Material Description</th>
                  <th scope="col">National Material Code</th>
                  <th scope="col">Standardized Specification</th>
                  <th scope="col">Mapping Status</th>
                </tr>
              </thead>
              <tbody>
                {filteredResults.flatMap((group) =>
                  (group.members || []).map((m, idx) => (
                    <tr key={`${group.commonMaterialCode || group.provisionalRef}-${idx}`}>
                      <td className="font-semibold text-accent">{m.cpseName}</td>
                      <td className="font-mono text-xs">{m.cpseMaterialCode}</td>
                      <td>{m.rawDescription}</td>
                      <td>
                        <CodeChip
                          code={group.commonMaterialCode}
                          size="sm"
                          categoryPath={group.categoryPath}
                        />
                      </td>
                      <td className="text-sm">{group.standardizedDescription}</td>
                      <td>
                        <span className={`badge ${m.mappingStatus === "CONFIRMED" ? "badge-success" : "badge-warning"}`}>
                          {m.mappingStatus}
                        </span>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </Table>
        </div>
      )}

      {!isLoading && !error && (searchResults?.totalPages || 0) > 0 && (
        <div className="card mt-3 p-3" aria-label="Catalog pagination">
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "1rem", flexWrap: "wrap" }}>
            <span className="text-sm text-muted">
              Page {(searchResults?.number ?? page) + 1} of {searchResults?.totalPages ?? 1}
              {Number.isFinite(searchResults?.totalElements) ? ` · ${searchResults.totalElements} records` : ""}
            </span>
            <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
              <label htmlFor="catalog-page-size" className="text-sm text-muted">Rows</label>
              <select
                id="catalog-page-size"
                className="form-select"
                value={pageSize}
                onChange={(event) => handlePageSizeChange(Number(event.target.value))}
                style={{ width: "84px" }}
              >
                {[20, 50, 100].map((option) => <option key={option} value={option}>{option}</option>)}
              </select>
              <button className="btn btn-outline btn-sm" onClick={() => handlePageChange(page - 1)} disabled={page <= 0}>
                Previous
              </button>
              <button
                className="btn btn-outline btn-sm"
                onClick={() => handlePageChange(page + 1)}
                disabled={Boolean(searchResults?.last) || page + 1 >= (searchResults?.totalPages ?? 1)}
              >
                Next
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Export dialog */}
      {showExportModal && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="export-modal-title">
          <div className="modal-card">
            <h2 id="export-modal-title" className="text-lg font-bold mb-3">
              Export Unified Catalog Data
            </h2>
            <p className="text-sm text-muted mb-4">
              Select desired catalog dataset and export format. All data exports write verifiable records to the governance audit trail.
            </p>

            {exportError && (
              <div className="alert alert-danger mb-4" role="alert" style={{ color: "var(--danger)", padding: "0.75rem", border: "1px solid var(--danger-border)", borderRadius: "var(--radius-sm)", fontSize: "0.85rem", background: "var(--danger-bg)" }}>
                {exportError}
              </div>
            )}

            <div className="form-group mb-3">
              <label className="form-label">Format</label>
              <div className="btn-group w-full">
                <button
                  type="button"
                  className={`btn ${exportFormat === "csv" ? "btn-primary" : "btn-outline"} w-1/2`}
                  onClick={() => setExportFormat("csv")}
                  disabled={isExporting}
                >
                  CSV (RFC 4180)
                </button>
                <button
                  type="button"
                  className={`btn ${exportFormat === "xlsx" ? "btn-primary" : "btn-outline"} w-1/2`}
                  onClick={() => setExportFormat("xlsx")}
                  disabled={isExporting}
                >
                  Excel (XLSX)
                </button>
              </div>
            </div>

            <div className="export-options-list mb-4">
              <button
                className="btn btn-outline btn-block text-left mb-2 flex items-center justify-between"
                onClick={() => handleDownloadExport("crossref")}
                disabled={isExporting}
              >
                <div>
                  <div className="font-semibold">Cross-Reference Index</div>
                  <div className="text-xs text-muted">CPSE plant codes mapped to catalog references</div>
                </div>
                <Download size={16} />
              </button>

              <button
                className="btn btn-outline btn-block text-left mb-2 flex items-center justify-between"
                onClick={() => handleDownloadExport("master")}
                disabled={isExporting}
              >
                <div>
                  <div className="font-semibold">Canonical Master Catalog</div>
                  <div className="text-xs text-muted">Complete UNSPSC-standard national master records</div>
                </div>
                <Download size={16} />
              </button>

              <button
                className="btn btn-outline btn-block text-left flex items-center justify-between"
                onClick={() => handleDownloadExport("erp")}
                disabled={isExporting}
              >
                <div>
                  <div className="font-semibold">SAP ERP Mapping Template</div>
                  <div className="text-xs text-muted">Standard MATNR, CATALOG_REF, MAKTX integration file</div>
                </div>
                <Download size={16} />
              </button>
            </div>

            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={() => { setShowExportModal(false); setExportError(null); }} disabled={isExporting}>
                {t("common.cancel")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
