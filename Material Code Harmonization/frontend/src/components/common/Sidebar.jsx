import React from "react";
import { NavLink } from "react-router-dom";
import { useAuth } from "../../context/useAuth";
import { useTranslation } from "react-i18next";
import { NAV } from "../../auth/roles";
import {
  LayoutDashboard,
  Boxes,
  UploadCloud,
  ClipboardCheck,
  BookOpen,
  GitCompare,
  ShieldCheck,
  Users,
} from "lucide-react";

const ICON_MAP = {
  LayoutDashboard,
  Boxes,
  UploadCloud,
  ClipboardCheck,
  BookOpen,
  GitCompare,
  ShieldCheck,
  Users,
};

export function Sidebar({ pendingCount = 0, mobileOpen = false, onNavigate }) {
  const { user } = useAuth();
  const { t } = useTranslation();
  const userRole = user?.role?.toUpperCase();

  const visibleNavItems = NAV.filter((item) =>
    userRole ? item.roles.includes(userRole) : false
  );

  return (
    <aside className={`app-sidebar ${mobileOpen ? "mobile-open" : ""}`} role="navigation" aria-label="Main Navigation">
      {/* Brand & Wordmark */}
      <div className="sidebar-brand">
        <div className="brand-logo-badge">
          <ShieldCheck size={16} className="brand-icon" aria-hidden="true" />
          <span className="brand-title">NUMM</span>
        </div>
        <span className="brand-sub">National Unified Material Master</span>
      </div>

      <div className="sidebar-divider" />

      {/* Navigation Links derived strictly from NAV */}
      <nav className="sidebar-nav">
        {visibleNavItems.map((item) => {
          const IconComponent = ICON_MAP[item.icon] || BookOpen;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              onClick={onNavigate}
              className={({ isActive }) => `nav-link ${isActive ? "active" : ""}`}
            >
              <IconComponent size={16} aria-hidden="true" style={{ color: "var(--ink-muted)" }} />
              <span>{t(item.key) || item.label}</span>
              {item.to === "/review" && pendingCount > 0 && (
                <span className="nav-badge" aria-label={`${pendingCount} items awaiting review`}>
                  {pendingCount}
                </span>
              )}
            </NavLink>
          );
        })}
      </nav>

      {/* Sidebar Footer */}
      <div className="sidebar-footer">
        <div>Material master workspace</div>
      </div>
    </aside>
  );
}
