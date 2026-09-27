import React, { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  UserPlus, Shield, Building2, CheckCircle2,
  AlertCircle, RefreshCw, X, Layers
} from "lucide-react";
import { api } from "../services/api";
import { useAuth } from "../context/useAuth";
import { ErrorPanel } from "./common/ErrorPanel";
import { LoadingSkeleton } from "./common/LoadingSkeleton";
import { Table } from "./common/Table";

const ROLE_OPTIONS = [
  { value: "OPERATOR", label: "CPSE Operator (Data Ingestion & Catalog Search)" },
  { value: "REVIEWER", label: "Master Reviewer (Four-Eyes Governance & Approvals)" },
  { value: "SENIOR_REVIEWER", label: "Senior Reviewer (Publication & Minting)" },
  { value: "ADMIN", label: "System Administrator (Security, Config & Overrides)" },
];

export function AdminUsersView() {
  const { user: currentUser } = useAuth();
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [createError, setCreateError] = useState(null);
  const [createSuccess, setCreateSuccess] = useState(null);

  // Form State
  const [formData, setFormData] = useState({
    name: "",
    email: "",
    password: "",
    role: "OPERATOR",
    cpseId: "",
    assignedCategoryIds: [],
  });

  const { data: users = [], isLoading: loading, error: usersError, refetch: loadUsers } = useQuery({
    queryKey: ["adminUsers"],
    queryFn: api.getAdminUsers,
  });
  const { data: options = { cpses: [], categories: [] } } = useQuery({
    queryKey: ["adminUserOptions"],
    queryFn: api.getAdminUserOptions,
  });
  const error = usersError?.message;

  const handleCreateUser = async (e) => {
    e.preventDefault();
    setCreateLoading(true);
    setCreateError(null);
    setCreateSuccess(null);

    try {
      const payload = {
        name: formData.name.trim(),
        email: formData.email.trim(),
        password: formData.password,
        role: formData.role,
        cpseId: formData.role === "OPERATOR" ? Number(formData.cpseId) : null,
        assignedCategoryIds: ["REVIEWER", "SENIOR_REVIEWER"].includes(formData.role)
          ? formData.assignedCategoryIds.map(Number)
          : [],
      };

      await api.createAdminUser(payload);
      setCreateSuccess(`User ${formData.name} successfully provisioned.`);
      setFormData({
        name: "",
        email: "",
        password: "",
        role: "OPERATOR",
        cpseId: options.cpses?.[0]?.id ? String(options.cpses[0].id) : "",
        assignedCategoryIds: [],
      });
      setTimeout(() => {
        setShowCreateModal(false);
        setCreateSuccess(null);
      }, 1500);
      loadUsers();
    } catch (err) {
      setCreateError(err.message || "Failed to create user.");
    } finally {
      setCreateLoading(false);
    }
  };

  const roleBadge = (role) => {
    switch (role) {
      case "ADMIN":
        return <span className="badge badge-danger"><Shield size={11} /> Admin</span>;
      case "REVIEWER":
        return <span className="badge badge-warning"><Layers size={11} /> Master Reviewer</span>;
      case "SENIOR_REVIEWER":
        return <span className="badge badge-info"><Shield size={11} /> Senior Reviewer</span>;
      case "OPERATOR":
      default:
        return <span className="badge badge-info"><Building2 size={11} /> Operator</span>;
    }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "1.25rem" }}>
      {/* Header */}
      <div className="page-header" style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: "1rem" }}>
        <div>
          <h1 className="page-title">User Management & CPSE Governance</h1>
          <p className="page-subtitle">
            Provision CPSE operators, assign master reviewers to UNSPSC categories, and configure role-based access control.
          </p>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button className="btn btn-ghost btn-sm" onClick={loadUsers} title="Refresh User List">
            <RefreshCw size={14} /> Refresh
          </button>
          <button className="btn btn-primary btn-sm" onClick={() => setShowCreateModal(true)}>
            <UserPlus size={14} /> Provision New User
          </button>
        </div>
      </div>

      {error ? (
        <ErrorPanel message={error} onRetry={loadUsers} />
      ) : loading ? (
        <LoadingSkeleton count={4} />
      ) : (
        <Table caption="NUMM user directory">
            <thead>
              <tr>
                <th scope="col">ID</th><th scope="col">Username</th><th scope="col">Email</th>
                <th scope="col">Role</th><th scope="col">CPSE Scope</th>
                <th scope="col">Department / Specialization</th><th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {users.length === 0 ? (
                <tr>
                  <td colSpan={7} style={{ textAlign: "center", padding: "2rem", color: "var(--text-muted)" }}>
                    No users found in directory.
                  </td>
                </tr>
              ) : (
                users.map((u) => (
                  <tr key={u.userId}>
                    <td>
                      <span className="text-mono text-xs" style={{ color: "var(--text-dim)" }}>
                        #{u.userId}
                      </span>
                    </td>
                    <td>
                      <span style={{ fontWeight: 600, color: "var(--text-primary)" }}>{u.name}</span>
                      {u.userId === currentUser?.userId && (
                        <span className="badge badge-success" style={{ marginLeft: "0.4rem", fontSize: "0.65rem" }}>
                          You
                        </span>
                      )}
                    </td>
                    <td style={{ fontSize: "0.8rem", color: "var(--text-secondary)" }}>{u.email}</td>
                    <td>{roleBadge(u.role)}</td>
                    <td>
                      {u.cpse ? (
                        <span className="badge badge-neutral">{u.cpse.name}</span>
                      ) : (
                        <span style={{ fontSize: "0.75rem", color: "var(--text-dim)", fontStyle: "italic" }}>
                          National / All
                        </span>
                      )}
                    </td>
                    <td style={{ fontSize: "0.8rem", color: "var(--text-secondary)" }}>
                      {u.assignedCategoryIds?.length
                        ? `${u.assignedCategoryIds.length} assigned categor${u.assignedCategoryIds.length === 1 ? "y" : "ies"}`
                        : "General Master Governance"}
                    </td>
                    <td>
                      <span className="badge badge-success" style={{ fontSize: "0.72rem" }}>
                        <CheckCircle2 size={11} /> Active
                      </span>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
        </Table>
      )}

      {/* Provision User Modal */}
      {showCreateModal && (
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
          <div className="card" style={{ width: "100%", maxWidth: "520px", boxShadow: "var(--shadow)" }}>
            <div className="card-header" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <div className="card-title">
                <UserPlus size={16} color="var(--accent)" />
                Provision New User
              </div>
              <button className="btn btn-ghost btn-xs" onClick={() => setShowCreateModal(false)}>
                <X size={16} />
              </button>
            </div>

            <form onSubmit={handleCreateUser}>
              <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: "1rem" }}>
                {createError && (
                  <div
                    style={{
                      background: "var(--danger-bg)",
                      border: "1px solid var(--danger-border)",
                      color: "var(--danger)",
                      padding: "0.6rem 0.8rem",
                      borderRadius: "var(--radius-sm)",
                      fontSize: "0.8rem",
                      display: "flex",
                      alignItems: "center",
                      gap: "0.5rem",
                    }}
                  >
                    <AlertCircle size={15} />
                    <span>{createError}</span>
                  </div>
                )}

                {createSuccess && (
                  <div
                    style={{
                      background: "var(--success-bg)",
                      border: "1px solid var(--success-border)",
                      color: "var(--success)",
                      padding: "0.6rem 0.8rem",
                      borderRadius: "var(--radius-sm)",
                      fontSize: "0.8rem",
                      display: "flex",
                      alignItems: "center",
                      gap: "0.5rem",
                    }}
                  >
                    <CheckCircle2 size={15} />
                    <span>{createSuccess}</span>
                  </div>
                )}

                <div>
                  <label className="form-label" style={{ fontSize: "0.78rem" }}>Full Name / Username *</label>
                  <input
                    type="text"
                    required
                    className="form-input"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    placeholder="e.g. Rahul Sharma"
                  />
                </div>

                <div>
                  <label className="form-label" style={{ fontSize: "0.78rem" }}>Official Enterprise Email *</label>
                  <input
                    type="email"
                    required
                    className="form-input"
                    value={formData.email}
                    onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                    placeholder="e.g. rahul.sharma@ongc.co.in"
                  />
                </div>

                <div>
                  <label className="form-label" style={{ fontSize: "0.78rem" }}>Initial Password *</label>
                  <input
                    type="password"
                    required
                    minLength={6}
                    className="form-input"
                    value={formData.password}
                    onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                    placeholder="Minimum 6 characters"
                  />
                </div>

                <div>
                  <label className="form-label" style={{ fontSize: "0.78rem" }}>System Role *</label>
                  <select
                    className="form-select"
                    value={formData.role}
                    onChange={(e) => setFormData({ ...formData, role: e.target.value })}
                  >
                    {ROLE_OPTIONS.map((r) => (
                      <option key={r.value} value={r.value}>{r.label}</option>
                    ))}
                  </select>
                </div>

                {formData.role === "OPERATOR" && (
                  <div>
                    <label className="form-label" style={{ fontSize: "0.78rem" }}>CPSE Enterprise *</label>
                    <select
                      className="form-select"
                      required
                      value={formData.cpseId}
                      onChange={(e) => setFormData({ ...formData, cpseId: e.target.value })}
                    >
                      <option value="">Select a CPSE</option>
                      {(options.cpses || []).map((c) => (
                        <option key={c.id} value={c.id}>{c.name}</option>
                      ))}
                    </select>
                  </div>
                )}

                {["REVIEWER", "SENIOR_REVIEWER"].includes(formData.role) && (
                  <fieldset className="category-assignment">
                    <legend className="form-label">Assigned review categories</legend>
                    {(options.categories || []).map((category) => (
                      <label key={category.id} className="checkbox-row">
                        <input
                          type="checkbox"
                          checked={formData.assignedCategoryIds.includes(String(category.id))}
                          onChange={(event) => setFormData((current) => ({
                            ...current,
                            assignedCategoryIds: event.target.checked
                              ? [...current.assignedCategoryIds, String(category.id)]
                              : current.assignedCategoryIds.filter((id) => id !== String(category.id)),
                          }))}
                        />
                        <span>{category.name}</span>
                      </label>
                    ))}
                  </fieldset>
                )}
              </div>

              <div
                className="card-footer"
                style={{
                  display: "flex",
                  justifyContent: "flex-end",
                  gap: "0.6rem",
                  padding: "0.85rem 1.25rem",
                  borderTop: "1px solid var(--border)",
                }}
              >
                <button
                  type="button"
                  className="btn btn-outline btn-sm"
                  onClick={() => setShowCreateModal(false)}
                  disabled={createLoading}
                >
                  Cancel
                </button>
                <button type="submit" className="btn btn-primary btn-sm" disabled={createLoading}>
                  {createLoading ? "Provisioning..." : "Provision User"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
