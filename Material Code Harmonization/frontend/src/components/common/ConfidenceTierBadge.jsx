import React from "react";
import { CheckCircle2, AlertTriangle, AlertCircle } from "lucide-react";
import { useTranslation } from "react-i18next";

export function ConfidenceTierBadge({ tier, score = null }) {
  const { t } = useTranslation();
  const normalizedTier = tier ? tier.toUpperCase() : "UNKNOWN";
  const pct = score !== null ? (score * 100).toFixed(1) + "%" : null;

  if (normalizedTier === "HIGH") {
    return (
      <span className="conf-badge conf-high" title={`Model Confidence: ${pct || "≥ 85%"}`}>
        <CheckCircle2 size={13} aria-hidden="true" />
        <span>{t("review.confHigh")}</span>
        {pct && <span className="conf-pct">· {pct}</span>}
      </span>
    );
  }

  if (normalizedTier === "MEDIUM") {
    return (
      <span className="conf-badge conf-mid" title={`Model Confidence: ${pct || "60% – 84%"}`}>
        <AlertTriangle size={13} aria-hidden="true" />
        <span>{t("review.confMid")}</span>
        {pct && <span className="conf-pct">· {pct}</span>}
      </span>
    );
  }

  if (normalizedTier === "LOW") return (
    <span className="conf-badge conf-low" title={`Model Confidence: ${pct || "< 60%"}`}>
      <AlertCircle size={13} aria-hidden="true" />
      <span>{t("review.confLow")}</span>
      {pct && <span className="conf-pct">· {pct}</span>}
    </span>
  );

  return <span className="conf-badge conf-low" title="No model confidence was recorded">Not recorded</span>;
}
