/**
 * Single source of truth for role permissions, routing, and navigation.
 */

export const NAV = [
  { to: "/dashboard", key: "nav.dashboard", roles: ["SENIOR_REVIEWER", "ADMIN"], label: "National KPI Dashboard", icon: "LayoutDashboard" },
  { to: "/my-materials", key: "nav.myMaterials", roles: ["OPERATOR"], label: "My CPSE Materials", icon: "Boxes" },
  { to: "/ingest", key: "nav.ingest", roles: ["OPERATOR", "ADMIN"], label: "CSV Catalog Ingestion", icon: "UploadCloud" },
  { to: "/review", key: "nav.review", roles: ["SENIOR_REVIEWER", "ADMIN"], label: "Review & Adjudication", icon: "ClipboardCheck" },
  { to: "/catalog", key: "nav.catalog", roles: ["OPERATOR", "SENIOR_REVIEWER", "ADMIN"], label: "Unified Material Catalog", icon: "BookOpen" },
  { to: "/compare", key: "nav.compare", roles: ["OPERATOR", "SENIOR_REVIEWER", "ADMIN"], label: "Pairwise AI Sandbox", icon: "GitCompare" },
  { to: "/audit", key: "nav.audit", roles: ["SENIOR_REVIEWER", "ADMIN"], label: "Cryptographic Audit Trail", icon: "ShieldCheck" },
  { to: "/admin/users", key: "nav.adminUsers", roles: ["ADMIN"], label: "User Administration", icon: "Users" },
];

export function rolesFor(route) {
  const item = NAV.find(({ to }) => to === route);
  if (!item) throw new Error(`No role definition exists for route ${route}`);
  return item.roles;
}

export function canAccessRoute(role, pathname) {
  const normalizedRole = role?.toUpperCase();
  if (!normalizedRole || !pathname) return false;
  const item = NAV.find(({ to }) => pathname === to || pathname.startsWith(`${to}/`));
  return item ? item.roles.includes(normalizedRole) : false;
}

/**
 * Returns the canonical landing page for a given role upon login.
 */
export function defaultRouteFor(role) {
  switch (role?.toUpperCase()) {
    case "OPERATOR":
      return "/my-materials";
    case "SENIOR_REVIEWER":
      return "/review";
    case "ADMIN":
      return "/dashboard";
    default:
      return "/catalog";
  }
}
