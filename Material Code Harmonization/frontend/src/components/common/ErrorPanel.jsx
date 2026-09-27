import React from "react";
import { AlertOctagon, RotateCw } from "lucide-react";
import { useTranslation } from "react-i18next";

export function ErrorPanel({ error, message: messageProp, onRetry, title = null }) {
  const { t } = useTranslation();
  const message = error?.message || (typeof error === "string" ? error : messageProp) || "Failed to communicate with backend service.";
  const status = error?.status || error?.details?.status;

  return (
    <div className="error-panel" role="alert" aria-live="assertive">
      <div className="error-panel-icon">
        <AlertOctagon size={16} color="var(--danger)" aria-hidden="true" />
      </div>
      <div className="error-panel-content">
        <h3 className="error-panel-title">
          {title || t("common.error")} {status && <span className="error-status">({status})</span>}
        </h3>
        <p className="error-panel-message">{message}</p>
        {onRetry && (
          <button className="btn btn-sm btn-outline-danger mt-2" onClick={onRetry}>
            <RotateCw size={13} aria-hidden="true" /> {t("common.retry")}
          </button>
        )}
      </div>
    </div>
  );
}
