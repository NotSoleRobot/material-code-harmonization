import React, { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../services/api";
import { Boxes, Search, Download, Plus } from "lucide-react";
import { Link } from "react-router-dom";
import { Table } from "./common/Table";

export function MyMaterialsView() {
  const [searchTerm, setSearchTerm] = useState("");

  const { data: materials = [], isLoading } = useQuery({
    queryKey: ["myMaterials"],
    queryFn: () => api.getMaterials(),
  });

  const filtered = materials.filter((m) => {
    const q = searchTerm.toLowerCase();
    return (
      (m.cpseMaterialCode && m.cpseMaterialCode.toLowerCase().includes(q)) ||
      (m.description && m.description.toLowerCase().includes(q)) ||
      (m.specification && m.specification.toLowerCase().includes(q))
    );
  });

  const exportCsv = () => api.downloadExport("cross-reference", "csv");

  return (
    <div className="my-materials-view page-container">
      <header className="page-header">
        <div className="flex items-center justify-between w-full">
          <div>
            <h1 className="page-title">My CPSE Materials Catalog</h1>
            <p className="page-subtitle">
              Catalog items submitted by your enterprise. Scoped strictly to your organization.
            </p>
          </div>
          <div className="page-actions">
            <button
              onClick={exportCsv}
              disabled={!filtered.length}
              className="btn btn-secondary"
            >
              <Download size={14} /> Export CSV
            </button>
            <Link
              to="/ingest"
              className="btn btn-primary"
            >
              <Plus size={14} /> Ingest New CSV
            </Link>
          </div>
        </div>
      </header>

      {/* Filter Bar */}
      <div className="filter-options-row mb-4">
        <div className="search-input-wrapper">
          <Search size={16} className="search-icon" aria-hidden="true" />
          <input
            type="text"
            placeholder="Search by code, description, or standard..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="form-input search-input"
          />
        </div>
        <span className="text-sm text-muted">
          Showing <strong>{filtered.length}</strong> of {materials.length} records
        </span>
      </div>

      {isLoading ? (
        <div className="empty-state" role="status" aria-live="polite">
          Loading catalog materials...
        </div>
      ) : filtered.length === 0 ? (
        <div className="empty-state card">
          <Boxes size={16} className="empty-state-icon" aria-hidden="true" />
          <h3 className="empty-state-title">
            No Materials Found
          </h3>
          <p className="empty-state-sub">
            {searchTerm ? "No records match your search criteria." : "Your CPSE has not ingested any catalog records yet."}
          </p>
          {!searchTerm && (
            <Link
              to="/ingest"
              className="btn btn-primary"
            >
              <Plus size={14} /> Ingest Legacy Catalog CSV
            </Link>
          )}
        </div>
      ) : (
        <Table caption="List of CPSE submitted materials">
            <thead>
              <tr>
                <th scope="col">CPSE Code</th><th scope="col">Material Description</th>
                <th scope="col">Specification / Standard</th><th scope="col">UOM</th>
                <th scope="col">Enterprise</th><th scope="col" className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((mat) => (
                <tr key={mat.materialId}>
                  <td className="table-code">
                    {mat.cpseMaterialCode}
                  </td>
                  <td>
                    {mat.description}
                  </td>
                  <td className="text-muted text-xs">
                    {mat.specification || "—"}
                  </td>
                  <td className="text-muted">
                    {mat.unitOfMeasure || "NOS"}
                  </td>
                  <td><span className="badge badge-neutral">
                      {mat.cpseName}
                    </span></td>
                  <td className="text-right">
                    <Link to="/compare" className="text-xs font-medium">
                      Compare in Sandbox
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
        </Table>
      )}
    </div>
  );
}
