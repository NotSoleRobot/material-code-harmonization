import React, { useState } from "react";
import { Copy, Check, Info } from "lucide-react";
import { useTranslation } from "react-i18next";

export function CodeChip({ code, size = "md", categoryPath = null }) {
  const [copied, setCopied] = useState(false);
  const [showTooltip, setShowTooltip] = useState(false);
  const { t } = useTranslation();

  if (!code) return <span className="text-muted">—</span>;

  const handleCopy = (e) => {
    e.stopPropagation();
    navigator.clipboard.writeText(code).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <span
      className={`numm-code-chip numm-code-${size} canonical`}
      onMouseEnter={() => setShowTooltip(true)}
      onMouseLeave={() => setShowTooltip(false)}
      role="button"
      tabIndex={0}
      onClick={handleCopy}
      onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && handleCopy(e)}
      title={`${t("catalog.referenceBadge")} - ${t("common.copyCode")}`}
      aria-label={`${code} ${copied ? t("common.copied") : t("common.copyCode")}`}
    >
      <span className="code-prefix">REF</span>
      <span className="code-value">{code}</span>
      <span className="copy-icon" aria-hidden="true">
        {copied ? <Check size={size === "lg" ? 14 : 12} color="var(--success)" /> : <Copy size={size === "lg" ? 14 : 12} />}
      </span>

      {showTooltip && categoryPath && (
        <div className="code-popover" role="tooltip">
          <div className="popover-header">
            <Info size={12} /> UNSPSC Classification
          </div>
          <div className="popover-body">{categoryPath}</div>
        </div>
      )}
    </span>
  );
}
