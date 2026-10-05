import React from "react";
import { useParams, Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api } from "../services/api";
import {
  ArrowLeft,
  Building2,
  GitFork,
  Hash,
} from "lucide-react";
import { CodeChip } from "./common/CodeChip";
import { LoadingSkeleton } from "./common/LoadingSkeleton";
import { ErrorPanel } from "./common/ErrorPanel";

export function CodeDetailView() {
  const { code } = useParams();

  const {
    data: details,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ["codeDetails", code],
    queryFn: () => api.getCodeDetails(code),
  });

  if (isLoading) {
    return (
      <div className="page-container">
        <LoadingSkeleton rows={5} type="card" />
      </div>
    );
  }

  if (error || !details) {
    return (
      <div className="page-container">
        <div className="mb-3">
          <Link to="/catalog" className="btn btn-ghost btn-sm">
            <ArrowLeft size={14} /> Back to Catalog
          </Link>
        </div>
        <ErrorPanel error={error || "Catalog record not found"} onRetry={refetch} />
      </div>
    );
  }

  const catalogReference = details.commonMaterialCode || details.provisionalRef;

  return (
    <div className="page-container">
      {/* Back Navigation */}
      <div className="mb-3">
        <Link to="/catalog" className="btn btn-ghost btn-sm">
          <ArrowLeft size={14} /> Back to Catalog
        </Link>
      </div>

      {/* Main Canonical Card */}
      <div className="card mb-4 p-4">
        <div className="detail-hero-header">
          <div>
            <div className="flex items-center gap-2 mb-2">
              <span className={`badge ${details.status === "HARMONIZED" ? "badge-success" : "badge-warning"}`}>
                {details.status === "HARMONIZED" ? "HARMONIZED" : "NEEDS REVIEW"}
              </span>
              <span className="badge badge-neutral">{details.categoryName}</span>
            </div>
            <h1 className="text-2xl font-bold font-mono text-accent flex items-center gap-3">
              <CodeChip code={catalogReference} provisional size="lg" />
            </h1>
          </div>

        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-4 pt-4 border-t border-border">
          <div>
            <span className="text-xs uppercase text-muted font-bold">Standardized Material Description</span>
            <p className="text-base font-semibold text-primary mt-1">{details.standardizedDescription}</p>

            {details.standardizedSpecification && (
              <div className="mt-3">
                <span className="text-xs uppercase text-muted font-bold">Standardized Specification</span>
                <p className="text-sm text-dim mt-0.5">{details.standardizedSpecification}</p>
              </div>
            )}
          </div>

          <div>
            <span className="text-xs uppercase text-muted font-bold">UNSPSC Classification Tree</span>
            <p className="text-sm font-medium text-accent mt-1">{details.categoryPath || "General Materials"}</p>
            <div className="flex gap-2 text-xs text-muted mt-2">
              <span>Segment: <strong>{details.codeSegment || "40"}</strong></span>
              <span>· Family: <strong>{details.codeFamily || "14"}</strong></span>
              <span>· Class: <strong>{details.codeClass || "07"}</strong></span>
            </div>
          </div>
        </div>

        {/* Attribute signature and provenance */}
        {details.attributeSignature && (
          <div className="mt-4 pt-3 border-t border-border">
            <div className="flex items-center justify-between">
              <span className="text-xs uppercase text-muted font-bold flex items-center gap-1">
                <Hash size={12} /> Deterministic Attribute Signature (SHA-256)
              </span>
              <span className="font-mono text-xs text-dim">{details.attributeSignature}</span>
            </div>

            {details.signatureAttributes && Object.keys(details.signatureAttributes).length > 0 && (
              <div className="flex flex-wrap gap-2 mt-2">
                {Object.entries(details.signatureAttributes).map(([k, v]) => (
                  <span key={k} className="badge badge-neutral text-xs">
                    {k}: <strong>{String(v)}</strong>
                  </span>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Member Plant Materials Across CPSEs */}
      <div className="card mb-4">
        <div className="card-header">
          <h2 className="card-title flex items-center gap-2">
            <Building2 size={16} /> Participating CPSE Master Records ({details.members?.length ?? 0} Mapped Items)
          </h2>
        </div>
        <div className="table-responsive">
          <table className="data-table" aria-label="Member Materials Table">
            <caption>Enterprise material records mapped to this harmonized catalog group</caption>
            <thead>
              <tr>
                <th scope="col">CPSE Enterprise</th>
                <th scope="col">Plant Material Code</th>
                <th scope="col">Raw Plant Description</th>
                <th scope="col">Unit</th>
                <th scope="col">Nominal Price</th>
                <th scope="col">Status</th>
                <th scope="col" className="text-right">Match Confidence</th>
              </tr>
            </thead>
            <tbody>
              {details.members && details.members.length > 0 ? (
                details.members.map((m) => (
                  <tr key={m.materialId}>
                    <td className="font-semibold text-accent">{m.cpseName}</td>
                    <td className="font-mono text-xs">{m.cpseMaterialCode}</td>
                    <td>
                      <div>{m.rawDescription}</div>
                      {m.rawSpecification && <div className="text-xs text-muted">{m.rawSpecification}</div>}
                    </td>
                    <td>{m.unitOfMeasure || "NOS"}</td>
                    <td className="font-mono text-xs">
                      {m.nominalPrice != null
                        ? `₹${Number(m.nominalPrice).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
                        : "—"}
                    </td>
                    <td>
                      <span className={`badge ${m.mappingStatus === "CONFIRMED" ? "badge-success" : "badge-warning"}`}>
                        {m.mappingStatus}
                      </span>
                    </td>
                    <td className="text-right font-mono text-xs">
                      {m.confidenceScore != null ? `${(m.confidenceScore * 100).toFixed(1)}%` : "Not recorded"}
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={7} className="text-center text-muted p-3">
                    No CPSE items linked yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Related equivalents and sibling variants */}
      {details.relatedGroups && details.relatedGroups.length > 0 && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title flex items-center gap-2">
              <GitFork size={16} /> Related Material Groups & Equivalents
            </h2>
          </div>
          <div className="table-responsive">
            <table className="data-table" aria-label="Related Groups Table">
              <caption>Equivalent and sibling material groups</caption>
              <thead>
                <tr>
                  <th scope="col">Relationship Type</th>
                  <th scope="col">Related Catalog Reference</th>
                  <th scope="col">Standardized Description</th>
                  <th scope="col" className="text-right">Action</th>
                </tr>
              </thead>
              <tbody>
                {details.relatedGroups.map((rg, i) => (
                  <tr key={i}>
                    <td>
                      <span className="badge badge-accent font-bold">{rg.relationType}</span>
                    </td>
                    <td>
                      <CodeChip code={rg.provisionalRef} provisional size="sm" />
                    </td>
                    <td>{rg.description}</td>
                    <td className="text-right">
                      <Link
                        to={`/catalog/${encodeURIComponent(rg.provisionalRef)}`}
                        className="btn btn-ghost btn-sm"
                      >
                        View Record
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
