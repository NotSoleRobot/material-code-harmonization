import React from "react";

/** Shared administrative table with an internal horizontal scroll boundary. */
export function Table({ caption, children, className = "data-table" }) {
  return (
    <div className="table-responsive">
      <table className={className}>
        <caption>{caption}</caption>
        {children}
      </table>
    </div>
  );
}
