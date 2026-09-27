import React from "react";
import { useAuth } from "../../context/useAuth";
import { useTranslation } from "react-i18next";
import { LogOut, Building2, User as UserIcon, ShieldAlert } from "lucide-react";

export function Header() {
  const { user, logout } = useAuth();
  const { t } = useTranslation();

  const getRoleBadgeClass = (role) => {
    switch (role) {
      case "ADMIN": return "badge-danger";
      case "SENIOR_REVIEWER": return "badge-warning";
      case "REVIEWER": return "badge-info";
      default: return "badge-neutral";
    }
  };

  return (
    <header className="app-header" role="banner">
      <div className="header-left">
        <a href="#main-content" className="skip-link">
          Skip to main content
        </a>
        <div className="header-org-chip">
          {user?.cpse ? (
            <>
              <Building2 size={14} className="text-accent" aria-hidden="true" />
              <span className="org-name">{user.cpse.name}</span>
              <span className="org-sector text-muted">({user.cpse.sector})</span>
            </>
          ) : (
            <>
              <ShieldAlert size={14} className="text-warning" aria-hidden="true" />
              <span className="org-name">Central Governance Body</span>
              <span className="org-sector text-muted">(MoPNG / National Committee)</span>
            </>
          )}
        </div>
      </div>

      <div className="header-right">
        {/* User Identity Chip */}
        {user && (
          <div className="user-profile-chip" role="region" aria-label="Current User Profile">
            <div className="avatar-circle">
              <UserIcon size={14} aria-hidden="true" />
            </div>
            <div className="user-details">
              <span className="user-name">{user.name}</span>
              <span className={`badge ${getRoleBadgeClass(user.role)} user-role-badge`}>
                {t(`roles.${user.role}`, user.role)}
              </span>
            </div>
            <button
              className="btn btn-ghost btn-icon btn-logout"
              onClick={logout}
              title={t("nav.logout")}
              aria-label={t("nav.logout")}
            >
              <LogOut size={15} aria-hidden="true" />
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
