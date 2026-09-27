import React from "react";
import { Link } from "react-router-dom";
import { ShieldX, ArrowLeft } from "lucide-react";
import { useAuth } from "../context/useAuth";
import { defaultRouteFor } from "../auth/roles";

export function AccessDeniedView() {
  const { user } = useAuth();

  return (
    <div className="access-denied-container">
      <div className="card text-center p-5 max-w-lg mx-auto">
        <div className="mb-3">
          <ShieldX size={16} color="var(--danger)" aria-hidden="true" />
        </div>
        <h1 className="text-2xl font-bold mb-2">403 — Access Restricted</h1>
        <p className="text-muted mb-4">
          Your current account role (<strong>{user?.role || "UNKNOWN"}</strong>) does not have authorization to view this administrative or governance resource.
        </p>
        <div>
          <Link to={defaultRouteFor(user?.role)} className="btn btn-primary">
            <ArrowLeft size={16} aria-hidden="true" /> Return to workspace
          </Link>
        </div>
      </div>
    </div>
  );
}
