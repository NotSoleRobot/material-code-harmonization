import React, { lazy, Suspense, useState } from "react";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { QueryClientProvider, useQuery } from "@tanstack/react-query";
import { queryClient } from "./queryClient";
import { AuthProvider } from "./context/AuthContext";
import { useAuth } from "./context/useAuth";
import { api } from "./services/api";
import { Header } from "./components/common/Header";
import { Sidebar } from "./components/common/Sidebar";
import { RequireAuth, RequireRole } from "./components/common/RouteGuards";
import { defaultRouteFor, rolesFor } from "./auth/roles";

// Views
import { LoginView } from "./components/LoginView";
import { AccessDeniedView } from "./components/AccessDeniedView";
const DashboardView = lazy(() => import("./components/DashboardView").then((m) => ({ default: m.DashboardView })));
const MyMaterialsView = lazy(() => import("./components/MyMaterialsView").then((m) => ({ default: m.MyMaterialsView })));
const CatalogView = lazy(() => import("./components/CatalogView").then((m) => ({ default: m.CatalogView })));
const CodeDetailView = lazy(() => import("./components/CodeDetailView").then((m) => ({ default: m.CodeDetailView })));
const ReviewQueueView = lazy(() => import("./components/ReviewQueueView").then((m) => ({ default: m.ReviewQueueView })));
const ReviewDetailView = lazy(() => import("./components/ReviewDetailView").then((m) => ({ default: m.ReviewDetailView })));
const IngestionWizard = lazy(() => import("./components/IngestionWizard").then((m) => ({ default: m.IngestionWizard })));
const HarmonizeView = lazy(() => import("./components/HarmonizeView").then((m) => ({ default: m.HarmonizeView })));
const AuditTrailView = lazy(() => import("./components/AuditTrailView").then((m) => ({ default: m.AuditTrailView })));
const AdminUsersView = lazy(() => import("./components/AdminUsersView").then((m) => ({ default: m.AdminUsersView })));

import "./i18n";

function AppLayout({ children }) {
  const { user } = useAuth();
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const canReview = user && ["ADMIN", "SENIOR_REVIEWER"].includes(user.role);

  const { data: pendingMappings } = useQuery({
    queryKey: ["mappings", "PENDING"],
    queryFn: () => api.getMappings("PENDING"),
    enabled: !!canReview,
    staleTime: 15_000,
  });

  const pendingCount = Array.isArray(pendingMappings) ? pendingMappings.length : 0;

  return (
    <div className="app-container">
      <Sidebar
        pendingCount={pendingCount}
        mobileOpen={mobileNavigationOpen}
        onNavigate={() => setMobileNavigationOpen(false)}
      />
      {mobileNavigationOpen && (
        <button
          type="button"
          className="sidebar-scrim"
          aria-label="Close navigation"
          onClick={() => setMobileNavigationOpen(false)}
        />
      )}
      <div className="app-main-wrapper">
        <Header onMenuToggle={() => setMobileNavigationOpen((open) => !open)} />
        <main id="main-content" className="main-content" tabIndex={-1}>
          {children}
        </main>
      </div>
    </div>
  );
}

function RoleDefaultRedirect() {
  const { user } = useAuth();
  const target = defaultRouteFor(user?.role);
  return <Navigate to={target} replace />;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Suspense fallback={<div className="loading-screen" role="status">Loading NUMM workspace…</div>}>
          <Routes>
            {/* Public Login & Error Pages */}
            <Route path="/login" element={<LoginView />} />
            <Route path="/access-denied" element={<AccessDeniedView />} />

            {/* Protected Application Routes */}
            <Route
              path="/*"
              element={
                <RequireAuth>
                  <AppLayout>
                    <Routes>
                      {/* Root landing page redirected by role */}
                      <Route path="/" element={<RoleDefaultRedirect />} />

                      {/* 1. National KPI Dashboard (SENIOR_REVIEWER, ADMIN) */}
                      <Route
                        path="/dashboard"
                        element={
                          <RequireRole roles={rolesFor("/dashboard")}>
                            <DashboardView />
                          </RequireRole>
                        }
                      />

                      {/* 2. My Materials (OPERATOR) */}
                      <Route
                        path="/my-materials"
                        element={
                          <RequireRole roles={rolesFor("/my-materials")}>
                            <MyMaterialsView />
                          </RequireRole>
                        }
                      />

                      {/* 3. CSV Ingestion Wizard (OPERATOR, ADMIN) */}
                      <Route
                        path="/ingest"
                        element={
                          <RequireRole roles={rolesFor("/ingest")}>
                            <IngestionWizard />
                          </RequireRole>
                        }
                      />

                      {/* 4. Review & Adjudication Queue (SENIOR_REVIEWER, ADMIN) */}
                      <Route
                        path="/review"
                        element={
                          <RequireRole roles={rolesFor("/review")}>
                            <ReviewQueueView />
                          </RequireRole>
                        }
                      />
                      <Route
                        path="/review/:id"
                        element={
                          <RequireRole roles={rolesFor("/review")}>
                            <ReviewDetailView />
                          </RequireRole>
                        }
                      />

                      {/* 5. Unified Material Catalog (All authenticated roles) */}
                      <Route path="/catalog" element={<CatalogView />} />
                      <Route path="/catalog/:code" element={<CodeDetailView />} />

                      {/* 6. Pairwise AI Comparison Sandbox (All authenticated roles) */}
                      <Route path="/compare" element={<HarmonizeView />} />
                      <Route path="/harmonize" element={<Navigate to="/compare" replace />} />

                      {/* 7. Cryptographic Audit Trail (SENIOR_REVIEWER, ADMIN) */}
                      <Route
                        path="/audit"
                        element={
                          <RequireRole roles={rolesFor("/audit")}>
                            <AuditTrailView />
                          </RequireRole>
                        }
                      />

                      {/* 8. User Administration (ADMIN) */}
                      <Route
                        path="/admin/users"
                        element={
                          <RequireRole roles={rolesFor("/admin/users")}>
                            <AdminUsersView />
                          </RequireRole>
                        }
                      />

                      {/* Catch-all fallback */}
                      <Route path="*" element={<RoleDefaultRedirect />} />
                    </Routes>
                  </AppLayout>
                </RequireAuth>
              }
            />
          </Routes>
          </Suspense>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

export default App;
