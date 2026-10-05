import React, { useState, useRef, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import Papa from "papaparse";
import {
  UploadCloud, CheckCircle2, AlertCircle, ArrowRight, ArrowLeft,
  RefreshCw, Check, FileText, Download,
  Info, Building2, Database, ExternalLink
} from "lucide-react";
import { api } from "../services/api";
import { useAuth } from "../context/useAuth";
import { ErrorPanel } from "./common/ErrorPanel";

const CANONICAL_FIELDS = [
  { key: "cpse_material_code", label: "Plant Material Code", required: true, desc: "Internal legacy ERP part number or material code (e.g. ONGC-P-101, SAP Code)" },
  { key: "description", label: "Material Description", required: true, desc: "Primary text description used for TF-IDF & attribute extraction" },
  { key: "specification", label: "Specification / Standard", required: false, desc: "Standard designation (e.g. ASTM A106, IS 1239, API 600)" },
  { key: "unit_of_measure", label: "Unit of Measure", required: false, desc: "Stock keeping unit (e.g., MTR, NOS, KG, SET)" },
  { key: "category", label: "Material Category", required: true, desc: "Primary commodity category (e.g., PIPE, VALVE, BEARING)" },
  { key: "cpse_name", label: "CPSE / Enterprise Name", required: false, desc: "Auto-injected from active enterprise session if omitted in file" },
];

const CPSE_LIST = [
  { code: "ONGC", name: "Oil & Natural Gas Corporation (ONGC)" },
  { code: "SAIL", name: "Steel Authority of India Limited (SAIL)" },
  { code: "IOCL", name: "Indian Oil Corporation Limited (IOCL)" },
  { code: "BHEL", name: "Bharat Heavy Electricals Limited (BHEL)" },
  { code: "GAIL", name: "GAIL (India) Limited" },
  { code: "NTPC", name: "NTPC Limited" },
  { code: "PGCIL", name: "Power Grid Corporation of India (PGCIL)" },
  { code: "CIL", name: "Coal India Limited (CIL)" },
  { code: "BPCL", name: "Bharat Petroleum Corporation (BPCL)" },
  { code: "HPCL", name: "Hindustan Petroleum Corporation (HPCL)" },
];

const HEADER_ALIASES = {
  cpse_material_code: ["code", "item_code", "mat_no", "material_code", "stock_no", "part_num", "part_no", "item_no", "sap_code", "erp_code", "matnr"],
  description: ["description", "desc", "material_description", "item_desc", "short_text", "material_name", "name", "maktx"],
  specification: ["spec", "specification", "standard", "grade", "astm", "is_standard", "material_grade"],
  unit_of_measure: ["uom", "unit", "base_uom", "measure_unit", "unit_of_measure", "units", "meins"],
  category: ["category", "commodity", "group", "class", "category_name", "material_group", "type", "matkl"],
  cpse_name: ["cpse", "plant", "company", "enterprise", "org", "organisation", "organization", "location", "unit", "werks"],
};

function isSupportedCategory(value) {
  return String(value || "").trim().length > 0;
}

function autoMap(headers) {
  const mapping = {};
  headers.forEach((h) => {
    const normalized = h.toLowerCase().trim().replace(/[\s-]/g, "_");
    for (const [canonical, aliases] of Object.entries(HEADER_ALIASES)) {
      if (aliases.some((a) => normalized.includes(a))) {
        if (!mapping[canonical]) mapping[canonical] = h;
        break;
      }
    }
  });
  return mapping;
}

const SAMPLE_TEMPLATES = {
  ONGC_SAP: `mat_no,short_text,spec_grade,base_uom,material_group
ONGC-PP-2001,CARBON STEEL PIPE DN50 SCH40 SMLS,IS 1239,MTR,PIPE
ONGC-VL-2002,GLOBE VLV 3INCH 300LB WCB RTJ,API 600,NOS,VALVE
ONGC-FL-2003,BLIND FLANGE 50MM 300#,ASME B16.5,NOS,FLANGE
ONGC-PP-2004,SS 316L PIPE 1IN SCH10S ASTM A312,ASTM A312,MTR,PIPE
ONGC-BR-2005,BALL BEARING 6308 2RS C3,ISO 15,NOS,BEARING`,
};

const STEPS = [
  { num: 1, name: "Upload Source File" },
  { num: 2, name: "Column Field Mapping" },
  { num: 3, name: "Pre-Flight Validation" },
  { num: 4, name: "Harmonization & Master View" },
];

export function IngestionWizard() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const isAdmin = user?.role === "ADMIN";
  const [step, setStep] = useState(1);
  const [file, setFile] = useState(null);
  const [headers, setHeaders] = useState([]);
  const [rows, setRows] = useState([]);
  const [mapping, setMapping] = useState({});
  // ADMIN defaults to "AUTO_DETECT" (smartly detects per-row enterprise from prefix/column)
  const [selectedCpse, setSelectedCpse] = useState(
    isAdmin ? "AUTO_DETECT" : (user?.cpse?.name || user?.cpse || "ONGC")
  );
  const [dragOver, setDragOver] = useState(false);
  const [error, setError] = useState(null);
  const [validationErrors, setValidationErrors] = useState([]);

  // Helper to resolve the CPSE for any given row
  const resolveRowCpse = (row) => {
    if (selectedCpse && selectedCpse !== "FROM_CSV" && selectedCpse !== "AUTO_DETECT") {
      return selectedCpse;
    }
    // 1. If mapped header has value
    if (mapping.cpse_name && row[mapping.cpse_name]) {
      const val = String(row[mapping.cpse_name]).trim();
      if (val) return val.toUpperCase();
    }
    // 2. Try inferring from code prefix (e.g. SAIL-PIP-1001 -> SAIL, ONGC-PP-2001 -> ONGC, BHEL-100 -> BHEL)
    const code = String((mapping.cpse_material_code ? row[mapping.cpse_material_code] : "") || "").trim();
    if (code) {
      const match = code.match(/^([A-Za-z0-9]{2,10})[-_:/]/);
      if (match && match[1] && !/^\d+$/.test(match[1])) {
        return match[1].toUpperCase();
      }
    }
    // 3. Fall back to user CPSE or GENERAL
    return (user?.cpse?.name || user?.cpse || "GENERAL").toUpperCase();
  };

  // Async Ingestion Job State
  const [jobId, setJobId] = useState(null);
  const [jobStatus, setJobStatus] = useState(null);
  const [jobProgress, setJobProgress] = useState(0);
  const [jobResult, setJobResult] = useState(null);
  const [uploadResult, setUploadResult] = useState(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [jobDiagnostics, setJobDiagnostics] = useState([]);

  const fileInputRef = useRef();
  const pollIntervalRef = useRef(null);
  const sseCleanupRef = useRef(null);

  useEffect(() => {
    return () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
      }
      if (sseCleanupRef.current) {
        sseCleanupRef.current();
      }
    };
  }, []);

  // PapaParse parsing
  const processCsvText = (csvText, fileName) => {
    Papa.parse(csvText, {
      header: true,
      skipEmptyLines: true,
      complete: (results) => {
        if (!results.meta.fields || results.meta.fields.length === 0) {
          setError("Could not parse file. Ensure it is a valid CSV with a header row.");
          return;
        }
        setHeaders(results.meta.fields);
        setRows(results.data);
        setMapping(autoMap(results.meta.fields));
        setFile({ name: fileName });
        setError(null);
        setStep(2);
      },
      error: (err) => {
        setError(`CSV Parse Error: ${err.message}`);
      },
    });
  };

  const handleFileDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    const f = e.dataTransfer.files[0];
    if (!f) return;
    if (!f.name.match(/\.(csv|tsv|txt)$/i)) {
      setError("Only .csv, .tsv, and .txt files are supported.");
      return;
    }
    const reader = new FileReader();
    reader.onload = (ev) => processCsvText(ev.target.result, f.name);
    reader.readAsText(f);
  };

  const handleFileInput = (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const reader = new FileReader();
    reader.onload = (ev) => processCsvText(ev.target.result, f.name);
    reader.readAsText(f);
  };

  const loadSample = (key) => {
    if (key === "ONGC_SAP") setSelectedCpse("ONGC");
    processCsvText(SAMPLE_TEMPLATES[key], `${key}_sample.csv`);
  };

  const updateMapping = (canonicalKey, sourceHeader) => {
    setMapping((prev) => ({
      ...prev,
      [canonicalKey]: sourceHeader === "" ? undefined : sourceHeader,
    }));
  };

  const validateMapping = () => {
    const missing = CANONICAL_FIELDS.filter((f) => f.required && !mapping[f.key]);
    if (missing.length > 0) {
      setValidationErrors([`Please map required fields: ${missing.map((f) => f.label).join(", ")}`]);
      return false;
    }
    setValidationErrors([]);
    return true;
  };

  // Pre-flight checks
  const previewRows = rows.slice(0, 6);
  const mappedFields = CANONICAL_FIELDS.filter((f) => mapping[f.key]);
  const requiredMappedFields = CANONICAL_FIELDS.filter((f) => f.required && mapping[f.key]);

  const hasRequiredFields = (row) =>
    requiredMappedFields.every((f) => row[mapping[f.key]] && String(row[mapping[f.key]]).trim() !== "");
  const hasSupportedCategory = (row) => isSupportedCategory(row[mapping.category]);
  const validCount = rows.filter((row) => hasRequiredFields(row) && hasSupportedCategory(row)).length;
  const incompleteCount = rows.filter((row) => !hasRequiredFields(row)).length;
  const unsupportedCategoryCount = rows.filter((row) => hasRequiredFields(row) && !hasSupportedCategory(row)).length;
  const warningCount = rows.length - validCount;

  // Transform rows to standard CSV for submission
  const generateStandardCsv = () => {
    const canonicalHeaders = ["cpse_material_code", "description", "specification", "unit_of_measure", "category", "cpse_name", "nominal_price"];
    const transformed = rows.map((r) => ({
      cpse_material_code: mapping.cpse_material_code ? r[mapping.cpse_material_code] || "" : "",
      description: mapping.description ? r[mapping.description] || "" : "",
      specification: mapping.specification ? r[mapping.specification] || "" : "",
      unit_of_measure: mapping.unit_of_measure ? r[mapping.unit_of_measure] || "NOS" : "NOS",
      category: mapping.category ? r[mapping.category] || "GENERAL_MRO" : "GENERAL_MRO",
      cpse_name: resolveRowCpse(r),
      nominal_price: mapping.nominal_price ? r[mapping.nominal_price] || "" : "",
    }));
    return Papa.unparse({ fields: canonicalHeaders, data: transformed });
  };

  const handleJobUpdate = (job) => {
    if (!job) return;
    const total = job.totalItems || rows.length;
    const processed = job.processedItems || 0;
    setJobStatus(job.status);
    setJobProgress(total > 0 ? Math.round((processed / total) * 100) : 50);
    if (job.diagnostics && Array.isArray(job.diagnostics) && job.diagnostics.length > 0) {
      setJobDiagnostics(job.diagnostics);
    }

    if (job.status === "COMPLETED" || job.status === "FAILED") {
      setJobResult(job);
      setIsSubmitting(false);
      if (job.status === "COMPLETED") {
        queryClient.invalidateQueries({ queryKey: ["materials"] });
        queryClient.invalidateQueries({ queryKey: ["codesSearch"] });
        queryClient.invalidateQueries({ queryKey: ["mappings"] });
      }
    }
  };

  // Asynchronous ingestion with SSE streaming (polling fallback).
  const handleIngest = async () => {
    setIsSubmitting(true);
    setError(null);
    setJobResult(null);
    setUploadResult(null);
    setJobId(null);
    setJobStatus(null);
    setJobProgress(0);
    setJobDiagnostics([]);

    const standardCsv = generateStandardCsv();

    try {
      const resp = await api.uploadBulkMaterials(standardCsv, true);
      setUploadResult(resp);
      if (resp && resp.jobId) {
        setJobId(resp.jobId);
        setJobStatus(resp.status || "QUEUED");
        subscribeToJob(resp.jobId);
      } else {
        // Synchronous completion response
        setJobResult(resp);
        setJobStatus("COMPLETED");
        setIsSubmitting(false);
      }
    } catch (err) {
      setError(err);
      setIsSubmitting(false);
    }
  };

  const subscribeToJob = (id) => {
    // Try SSE first; fall back to polling if unavailable
    const cleanup = api.streamJobEvents(
      id,
      (job) => handleJobUpdate(job),
      () => {
        // SSE failed — fall back to interval polling
        pollJob(id);
      },
    );
    if (cleanup) {
      sseCleanupRef.current = cleanup;
    } else {
      pollJob(id);
    }
  };

  const pollJob = (id) => {
    if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
    pollIntervalRef.current = setInterval(async () => {
      try {
        const job = await api.getJobStatus(id);
        if (job) {
          handleJobUpdate(job);
          if (job.status === "COMPLETED" || job.status === "FAILED") {
            clearInterval(pollIntervalRef.current);
            pollIntervalRef.current = null;
          }
        }
      } catch (err) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
        setError("Error polling ingestion job status: " + err.message);
        setIsSubmitting(false);
      }
    }, 1500);
  };

  const resetWizard = () => {
    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
      pollIntervalRef.current = null;
    }
    setStep(1);
    setFile(null);
    setHeaders([]);
    setRows([]);
    setMapping({});
    setError(null);
    setJobId(null);
    setJobStatus(null);
    setJobResult(null);
    setUploadResult(null);
    setJobProgress(0);
    setIsSubmitting(false);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "1.25rem" }}>
      {/* Header */}
      <div className="page-header">
        <div>
          <h1 className="page-title">Material Ingestion</h1>
          <p className="page-subtitle">Upload a material file, confirm its columns, validate the records, and submit it for harmonization.</p>
        </div>
      </div>

      <div className="card">
        <div className="card-body">
          {/* Step Progress */}
          <div className="wizard-progress">
            {STEPS.map((s) => {
              const state = s.num < step ? "completed" : s.num === step ? "active" : "pending";
              return (
                <div key={s.num} className={`wizard-step ${state}`}>
                  <div className="step-circle">
                    {state === "completed" ? <Check size={12} /> : s.num}
                  </div>
                  <div className="step-info">
                    <div className="step-num">Step {s.num}</div>
                    <div className="step-name">{s.name}</div>
                  </div>
                </div>
              );
            })}
          </div>

          {error && (
            <ErrorPanel
              error={error}
              title={step === 4 ? "Ingestion could not be completed" : null}
              onRetry={step === 4 ? handleIngest : () => setError(null)}
            />
          )}

          {/* STEP 1: FILE UPLOAD */}
          {step === 1 && (
            <div>
              {/* Enterprise selector */}
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  background: "var(--bg-subtle)",
                  border: "1px solid var(--border)",
                  borderRadius: "var(--radius-md)",
                  padding: "0.85rem 1.15rem",
                  marginBottom: "1.25rem",
                  flexWrap: "wrap",
                  gap: "0.75rem",
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
                  <Building2 size={16} color="var(--accent)" />
                  <div>
                    <span style={{ fontSize: "0.75rem", color: "var(--text-muted)", textTransform: "uppercase", fontWeight: 700, display: "block" }}>
                      Submitting organization
                    </span>
                    <span style={{ fontSize: "0.9rem", fontWeight: 700, color: "var(--text-primary)" }}>
                      {selectedCpse === "AUTO_DETECT" || selectedCpse === "FROM_CSV"
                        ? "✨ Auto-detected per record (from file or code prefix)"
                        : (CPSE_LIST.find((c) => c.code === selectedCpse)?.name || selectedCpse)}
                    </span>
                  </div>
                </div>

                {/* Enterprise selector — Admin sees full dropdown + auto-detect option; others see their fixed enterprise */}
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                  <span style={{ fontSize: "0.78rem", color: "var(--text-secondary)" }}>Organization</span>
                  {isAdmin ? (
                    <select
                      className="form-select"
                      style={{ fontSize: "0.8rem", padding: "0.3rem 0.6rem", width: "240px" }}
                      value={selectedCpse}
                      onChange={(e) => setSelectedCpse(e.target.value)}
                    >
                      <option value="AUTO_DETECT">✨ Auto-Detect from File / Code</option>
                      {CPSE_LIST.map((c) => (
                        <option key={c.code} value={c.code}>{c.name}</option>
                      ))}
                    </select>
                  ) : (
                    <span style={{ fontSize: "0.85rem", fontWeight: 700, color: "var(--text-primary)" }}>
                      {CPSE_LIST.find((c) => c.code === selectedCpse)?.code || selectedCpse}
                    </span>
                  )}
                </div>
              </div>

              {/* Dropzone */}
              <div
                className={`drop-zone ${dragOver ? "drag-over" : ""}`}
                onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
                onDragLeave={() => setDragOver(false)}
                onDrop={handleFileDrop}
                onClick={() => fileInputRef.current?.click()}
              >
                <div className="drop-zone-icon"><UploadCloud size={16} strokeWidth={1.5} /></div>
                <div className="drop-zone-title">Drop a material file here</div>
                <div className="drop-zone-sub">or click to select a CSV, TSV, or TXT file</div>
                <input ref={fileInputRef} type="file" accept=".csv,.tsv,.txt" style={{ display: "none" }} onChange={handleFileInput} />
              </div>

              {/* Sample Benchmarks */}
              <div style={{ marginTop: "1.5rem" }}>
                <div style={{ fontSize: "0.78rem", fontWeight: 600, color: "var(--text-muted)", textTransform: "uppercase", letterSpacing: "0.05em", marginBottom: "0.75rem" }}>
                  Sample file
                </div>
                <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap" }}>
                  <button className="btn btn-outline btn-sm" onClick={() => loadSample("ONGC_SAP")}>
                    <Download size={13} /> Load sample materials (5 rows)
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* STEP 2: COLUMN MAPPING */}
          {step === 2 && (
            <div>
              <div
                style={{
                  display: "flex",
                  gap: "0.75rem",
                  alignItems: "flex-start",
                  background: "var(--info-bg)",
                  border: "1px solid var(--info-border)",
                  borderRadius: "var(--radius-md)",
                  padding: "0.9rem 1.15rem",
                  marginBottom: "1.25rem",
                }}
              >
                <Info size={16} color="var(--info)" style={{ flexShrink: 0, marginTop: "2px" }} />
                <div style={{ fontSize: "0.825rem", color: "var(--info)", lineHeight: 1.5 }}>
                  Match each file column to the corresponding material field. Organization will be{" "}
                  <strong>{selectedCpse === "AUTO_DETECT" || selectedCpse === "FROM_CSV" ? "Auto-detected per record (from file column or code prefix)" : selectedCpse}</strong>.
                </div>
              </div>

              {validationErrors.length > 0 && (
                <div style={{ background: "var(--danger-bg)", border: "1px solid var(--danger-border)", color: "var(--danger)", padding: "0.75rem", borderRadius: "var(--radius-sm)", marginBottom: "1rem", fontSize: "0.8rem" }}>
                  {validationErrors.map((e, idx) => <div key={idx}>{e}</div>)}
                </div>
              )}

              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1rem", flexWrap: "wrap", gap: "0.5rem" }}>
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                  <FileText size={16} color="var(--accent)" />
                  <span style={{ fontSize: "0.875rem", fontWeight: 600, color: "var(--text-secondary)" }}>
                    {file?.name} — {rows.length} rows, {headers.length} columns detected
                  </span>
                </div>
                <button className="btn btn-ghost btn-xs" onClick={() => setMapping(autoMap(headers))}>
                  <RefreshCw size={12} /> Re-run Auto-Detection
                </button>
              </div>

              <div className="table-wrapper" style={{ marginBottom: "1.5rem" }}>
                <table className="data-table">
                  <caption>Source-column mapping to NUMM canonical fields</caption>
                  <thead>
                    <tr>
                      <th scope="col" style={{ width: "240px" }}>NUMM Canonical Field</th>
                      <th scope="col" style={{ width: "90px" }}>Requirement</th>
                      <th scope="col" style={{ width: "240px" }}>Map to Your File Header</th>
                      <th scope="col">Sample Extracted Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    {CANONICAL_FIELDS.map((field) => {
                      const sourceCol = mapping[field.key];
                      const sampleVal = sourceCol && rows[0] ? rows[0][sourceCol] : null;
                      const isCpseField = field.key === "cpse_name";

                      return (
                        <tr key={field.key}>
                          <td>
                            <span style={{ fontWeight: 600, color: "var(--text-primary)", fontSize: "0.825rem" }}>{field.label}</span>
                            <div style={{ fontSize: "0.72rem", color: "var(--text-muted)", marginTop: "2px" }}>{field.desc}</div>
                          </td>
                          <td>
                            {field.required ? (
                              <span className="badge badge-danger">Required</span>
                            ) : (
                              <span className="badge badge-neutral">Optional</span>
                            )}
                          </td>
                          <td>
                            <select
                              className="form-select"
                              style={{ width: "100%", fontSize: "0.825rem" }}
                              value={sourceCol || ""}
                              onChange={(e) => updateMapping(field.key, e.target.value)}
                            >
                              <option value="">{isCpseField ? (selectedCpse === "AUTO_DETECT" || selectedCpse === "FROM_CSV" ? "— Auto-detect per row —" : `— Auto-assign: ${selectedCpse} —`) : "— Not mapped —"}</option>
                              {headers.map((h) => (
                                <option key={h} value={h}>{h}</option>
                              ))}
                            </select>
                          </td>
                          <td>
                            {isCpseField && !sourceCol ? (
                              <span className="badge badge-success" style={{ fontSize: "0.75rem" }}>
                                {rows[0] ? resolveRowCpse(rows[0]) : selectedCpse}
                              </span>
                            ) : sampleVal ? (
                              <span style={{ fontFamily: "var(--font-mono)", fontSize: "0.75rem", color: "var(--text-secondary)", background: "var(--bg-subtle)", padding: "0.15rem 0.4rem", borderRadius: "var(--radius-xs)", border: "1px solid var(--border)" }}>
                                {sampleVal}
                              </span>
                            ) : (
                              <span style={{ fontSize: "0.75rem", color: "var(--text-dim)", fontStyle: "italic" }}>
                                {field.required ? "Unmapped" : "Optional"}
                              </span>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <button className="btn btn-ghost btn-sm" onClick={() => setStep(1)}>
                  <ArrowLeft size={14} /> Back to File Upload
                </button>
                <button
                  className="btn btn-primary"
                  onClick={() => {
                    if (validateMapping()) setStep(3);
                  }}
                >
                  Validate & Preview Data <ArrowRight size={14} />
                </button>
              </div>
            </div>
          )}

          {/* STEP 3: PRE-FLIGHT VALIDATION */}
          {step === 3 && (
            <div>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: "0.75rem", marginBottom: "1.25rem" }}>
                <div style={{ background: "var(--success-bg)", border: "1px solid var(--success-border)", borderRadius: "var(--radius-sm)", padding: "0.75rem 1rem", fontSize: "0.8rem", color: "var(--success-text)" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.4rem", fontWeight: 700, marginBottom: "0.2rem" }}>
                    <CheckCircle2 size={15} color="var(--success)" /> Valid Records
                  </div>
                  <div><strong>{validCount}</strong> of {rows.length} rows ready for ingestion</div>
                </div>

                <div style={{ background: "var(--info-bg)", border: "1px solid var(--info-border)", borderRadius: "var(--radius-sm)", padding: "0.75rem 1rem", fontSize: "0.8rem", color: "var(--info-text)" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.4rem", fontWeight: 700, marginBottom: "0.2rem" }}>
                    <Building2 size={15} color="var(--info)" /> Enterprise Association
                  </div>
                  <div>
                    Target: <strong>{selectedCpse === "AUTO_DETECT" || selectedCpse === "FROM_CSV" ? "Dynamic per record" : selectedCpse}</strong>
                  </div>
                </div>

                <div style={{ background: warningCount > 0 ? "var(--warning-bg)" : "var(--bg-subtle)", border: `1px solid ${warningCount > 0 ? "var(--warning-border)" : "var(--border)"}`, borderRadius: "var(--radius-sm)", padding: "0.75rem 1rem", fontSize: "0.8rem", color: warningCount > 0 ? "var(--warning-text)" : "var(--text-secondary)" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.4rem", fontWeight: 700, marginBottom: "0.2rem" }}>
                    <AlertCircle size={15} color={warningCount > 0 ? "var(--warning)" : "var(--text-dim)"} /> Rows Requiring Attention
                  </div>
                  <div>
                    {warningCount > 0
                      ? `${incompleteCount} incomplete; ${unsupportedCategoryCount} missing a category`
                      : "All rows are structurally complete and ready for ingestion"}
                  </div>
                </div>
              </div>

              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.6rem" }}>
                <span style={{ fontSize: "0.825rem", fontWeight: 600, color: "var(--text-secondary)" }}>
                  Pre-flight Sample Preview (first {previewRows.length} rows)
                </span>
                <span className="badge badge-neutral">Batch size: {rows.length} records</span>
              </div>

              <div className="table-wrapper" style={{ marginBottom: "1.5rem" }}>
                <table className="data-table">
                  <caption>Pre-flight sample of records ready for ingestion</caption>
                  <thead>
                    <tr>
                      <th scope="col" style={{ width: "45px" }}>Status</th>
                      <th scope="col" style={{ width: "80px" }}>CPSE</th>
                      {mappedFields.map((f) => <th scope="col" key={f.key}>{f.label}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {previewRows.map((row, i) => {
                      const fieldsComplete = hasRequiredFields(row);
                      const categorySupported = hasSupportedCategory(row);
                      const isValid = fieldsComplete && categorySupported;
                      return (
                        <tr key={i} style={!isValid ? { background: "var(--danger-bg)" } : {}}>
                          <td>
                            {isValid ? (
                              <CheckCircle2 size={14} color="var(--success)" />
                            ) : (
                              <AlertCircle
                                size={14}
                                color="var(--danger)"
                                title={fieldsComplete ? "Category is outside the configured taxonomy" : "Missing required field"}
                              />
                            )}
                          </td>
                          <td><span className="badge badge-neutral">{resolveRowCpse(row)}</span></td>
                          {mappedFields.map((f) => {
                            const val = row[mapping[f.key]];
                            return (
                              <td key={f.key} style={{ maxWidth: "200px" }}>
                                <span className="truncate" style={{ display: "block", fontSize: "0.8rem" }}>
                                  {val || "—"}
                                </span>
                              </td>
                            );
                          })}
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <button className="btn btn-ghost btn-sm" onClick={() => setStep(2)}>
                  <ArrowLeft size={14} /> Back to Mapping
                </button>
                <button
                  className="btn btn-primary btn-lg"
                  disabled={validCount === 0}
                  onClick={() => {
                    setStep(4);
                    handleIngest();
                  }}
                >
                  <UploadCloud size={16} /> Ingest {validCount} Materials & Run Harmonization
                </button>
              </div>
            </div>
          )}

          {/* STEP 4: ASYNC INGESTION PROGRESS & RESULTS */}
          {step === 4 && (
            <div>
              {isSubmitting && (
                <div style={{ textAlign: "center", padding: "3rem 0" }} role="status" aria-live="polite">
                  <div style={{ fontWeight: 600, color: "var(--text-primary)", marginBottom: "0.35rem", fontSize: "1rem" }}>
                    Executing AI Harmonization Pipeline...
                  </div>
                  <div style={{ fontSize: "0.8rem", color: "var(--text-muted)", maxWidth: "480px", margin: "0 auto 1.5rem auto" }}>
                    Evaluating {rows.length} materials against National Unified Master records via TF-IDF, Attribute Verification, and Random Forest Classifier.
                  </div>

                  {/* Asynchronous Progress Bar */}
                  {jobId && (
                    <div style={{ maxWidth: "400px", margin: "0 auto" }}>
                      <div style={{ display: "flex", justifyContent: "space-between", fontSize: "0.75rem", color: "var(--text-secondary)", marginBottom: "4px" }}>
                        <span>Job #{jobId} {jobStatus ? `(${jobStatus})` : ""}</span>
                        <span>{jobProgress}%</span>
                      </div>
                      <div style={{ width: "100%", height: "8px", background: "var(--bg-subtle)", borderRadius: "var(--radius)", overflow: "hidden", border: "1px solid var(--border)" }}>
                        <div
                          style={{
                            width: `${jobProgress}%`,
                            height: "100%",
                            background: "var(--accent)",
                          }}
                        />
                      </div>
                    </div>
                  )}
                </div>
              )}

              {!isSubmitting && jobResult?.status === "FAILED" && (
                <ErrorPanel
                  title="Harmonization job failed"
                  error={jobResult.errorMessage || "The backend could not complete this batch."}
                  onRetry={resetWizard}
                />
              )}

              {!isSubmitting && jobResult && jobResult.status !== "FAILED" && (
                <div>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.6rem", marginBottom: "1.25rem" }}>
                    <div>
                      <span style={{ fontWeight: 700, color: "var(--text-primary)", fontSize: "1.05rem", display: "block" }}>
                        Ingestion & Harmonization Complete
                      </span>
                      <span style={{ fontSize: "0.78rem", color: "var(--text-muted)" }}>
                        New records were queued for harmonization; repeat records were detected and left unchanged.
                      </span>
                    </div>
                  </div>

                  {((jobResult?.skippedItems ?? uploadResult?.skippedCount ?? 0) > 0 ||
                    (jobResult?.alreadyHarmonized ?? 0) > 0) && (
                    <div
                      role="status"
                      style={{
                        display: "flex",
                        gap: "0.75rem",
                        padding: "0.9rem 1rem",
                        marginBottom: "1.25rem",
                        background: "var(--warning-bg)",
                        border: "1px solid var(--warning-border)",
                        borderRadius: "var(--radius-md)",
                        color: "var(--warning-text)",
                      }}
                    >
                      <AlertCircle size={18} style={{ flexShrink: 0, marginTop: "2px" }} />
                      <div>
                        <div style={{ fontWeight: 700, marginBottom: "0.35rem" }}>
                          {jobResult?.importedItems ?? uploadResult?.importedCount ?? 0} new; {jobResult?.alreadyHarmonized ?? 0} already harmonized or queued; {jobResult?.skippedItems ?? uploadResult?.skippedCount ?? 0} incomplete
                        </div>
                        <div style={{ fontSize: "0.78rem", marginBottom: "0.35rem" }}>
                          Existing CPSE material codes with an active mapping are not processed again. Only new or previously unmapped records enter harmonization.
                        </div>
                        <ul style={{ margin: 0, paddingLeft: "1.1rem", fontSize: "0.76rem" }}>
                          {((jobDiagnostics && jobDiagnostics.length > 0 ? jobDiagnostics : uploadResult?.messages) || [])
                            .filter((message) => typeof message === "string" && message.startsWith("Row "))
                            .map((message, index) => <li key={`${index}-${message}`}>{message}</li>)}
                        </ul>
                      </div>
                    </div>
                  )}

                  {/* Result KPI Cards: input accounting and routing outcomes are intentionally separate. */}
                  <div className="ingest-result-grid" style={{ marginBottom: "1.5rem" }}>
                    <div className="ingest-result-card result-auto">
                      <div className="result-count">{jobResult.totalItems ?? rows.length ?? 0}</div>
                      <div className="result-label">Uploaded Records</div>
                      <div className="result-desc">Total rows received in this batch</div>
                    </div>
                    <div className="ingest-result-card result-review">
                      <div className="result-count">{jobResult.alreadyHarmonized ?? 0}</div>
                      <div className="result-label">Already Harmonized</div>
                      <div className="result-desc">Existing source codes left unchanged</div>
                    </div>
                    <div className="ingest-result-card result-review">
                      <div className="result-count">{jobResult.skippedItems ?? 0}</div>
                      <div className="result-label">Not Processed</div>
                      <div className="result-desc">Invalid or incomplete rows</div>
                    </div>
                  </div>

                  <div className="text-sm font-semibold text-primary" style={{ marginBottom: "0.65rem" }}>
                    Harmonization outcomes ({jobResult.queuedForHarmonization ?? 0} processed)
                  </div>
                  <div className="ingest-result-grid" style={{ marginBottom: "1.5rem" }}>
                    <div className="ingest-result-card result-auto">
                      <div className="result-count">{jobResult.autoHarmonized ?? 0}</div>
                      <div className="result-label">Directly Harmonized</div>
                      <div className="result-desc">Matched to an existing code or assigned a new NUMM code</div>
                    </div>
                    <div className="ingest-result-card result-novel">
                      <div className="result-count">{jobResult.pendingReview ?? 0}</div>
                      <div className="result-label">Review Required</div>
                      <div className="result-desc">Ambiguous matches or incomplete identities awaiting review</div>
                    </div>
                  </div>

                  <div style={{ display: "flex", gap: "0.75rem", marginTop: "1.5rem", flexWrap: "wrap", alignItems: "center" }}>
                    <button className="btn btn-outline" onClick={resetWizard}>
                      <UploadCloud size={14} /> Upload Another Batch
                    </button>
                    <button
                      className="btn btn-primary"
                      onClick={() => navigate(user?.role === "OPERATOR" ? "/my-materials" : "/review")}
                    >
                      <Database size={14} /> {user?.role === "OPERATOR" ? "View My Materials" : "Go to Review Queue"}
                    </button>
                    <button className="btn btn-ghost" onClick={() => navigate("/catalog")}>
                      <ExternalLink size={14} /> View Master Catalog
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
