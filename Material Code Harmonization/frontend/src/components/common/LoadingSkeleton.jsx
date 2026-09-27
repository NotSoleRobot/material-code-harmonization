import React from "react";

export function LoadingSkeleton({ rows = 4, type = "table" }) {
  if (type === "card") {
    return (
      <div className="skeleton-grid" role="status" aria-busy="true" aria-label="Loading content">
        {Array.from({ length: rows }).map((_, i) => (
          <div key={i} className="card skeleton-card">
            <div className="skeleton-line skeleton-title" />
            <div className="skeleton-line skeleton-sub" />
            <div className="skeleton-line skeleton-text" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="skeleton-table" role="status" aria-busy="true" aria-label="Loading table data">
      <div className="skeleton-line skeleton-header" />
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="skeleton-line skeleton-row" />
      ))}
    </div>
  );
}
