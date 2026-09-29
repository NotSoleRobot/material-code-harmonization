import React, { useState, useEffect } from "react";
import { useAuth } from "../context/useAuth";
import { useNavigate, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../services/api";
import { defaultRouteFor } from "../auth/roles";
import {
  ShieldCheck,
  AlertCircle,
  ArrowRight,
  Lock,
  Mail,
  RefreshCw,
} from "lucide-react";

export function LoginView() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [lastAttempt, setLastAttempt] = useState(null);
  const [demoAccounts, setDemoAccounts] = useState([]);

  useEffect(() => {
    api.getDemoAccounts().then((accounts) => {
      if (Array.isArray(accounts) && accounts.length > 0) {
        setDemoAccounts(accounts);
      }
    }).catch((err) => {
      setError({
        title: "Service unavailable",
        message: err.message || "The backend could not provide login configuration.",
        retryable: false,
      });
    });
  }, []);

  const runLogin = async (loginEmail, loginPassword) => {
    if (!loginEmail || !loginPassword) {
      setError({ title: "Validation Error", message: "Please provide both email and password." });
      return;
    }

    setLoading(true);
    setError(null);
    setLastAttempt({ email: loginEmail, password: loginPassword });
    try {
      const response = await login(loginEmail, loginPassword);
      const userRole = response?.user?.role;
      const target = location.state?.from?.pathname || defaultRouteFor(userRole);
      navigate(target, { replace: true });
    } catch (err) {
      setError({
        title: err.code === "SERVICE_UNAVAILABLE" || err.status === 0
          ? "Service unavailable"
          : "Sign-in failed",
        message: err.message || t("auth.invalidCreds"),
        retryable: err.code === "SERVICE_UNAVAILABLE" || err.status === 0,
      });
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e) => {
    e?.preventDefault();
    await runLogin(email, password);
  };

  const handleDemoSignIn = async (demoEmail, demoPassword) => {
    setEmail(demoEmail);
    setPassword(demoPassword);
    await runLogin(demoEmail, demoPassword);
  };

  return (
    <div className="login-page">
      <header className="login-masthead">
        <div className="login-masthead-inner">
          <div className="login-masthead-brand">
            <div className="login-logo"><ShieldCheck size={16} aria-hidden="true" /></div>
            <div>
              <strong>NUMM</strong>
              <span>National Unified Material Master</span>
            </div>
          </div>
          <div className="login-masthead-meta">Enterprise Material Governance</div>
        </div>
      </header>

      <main className="login-main" style={{ display: "flex", justifyContent: "center", padding: "40px 20px" }}>
        <section className="login-panel" aria-labelledby="login-title" style={{ maxWidth: "480px", width: "100%" }}>
          <div className="login-panel-heading">
            <div className="brand-badge-lg"><Lock size={16} aria-hidden="true" /></div>
            <div>
              <span className="login-panel-kicker">Administrative Authentication</span>
              <h2 id="login-title">Sign in to NUMM Portal</h2>
            </div>
          </div>
          <p className="login-panel-sub">Enter your enterprise credentials.</p>

          {error && (
            <div className="login-alert" role="alert" aria-live="assertive">
              <AlertCircle size={16} aria-hidden="true" />
              <div>
                <strong>{error.title || "Sign-in failed"}</strong>
                <span>{error.message || error}</span>
              </div>
              {error.retryable && lastAttempt && (
                <button type="button" className="login-retry" onClick={() => runLogin(lastAttempt.email, lastAttempt.password)} disabled={loading}>
                  <RefreshCw size={14} aria-hidden="true" /> Retry
                </button>
              )}
            </div>
          )}

          <form onSubmit={handleSubmit} className="login-form" noValidate>
            <div className="form-group">
              <label htmlFor="email-input" className="form-label">
                {t("auth.email")}
              </label>
              <div className="input-with-icon">
                <Mail size={16} className="input-icon" aria-hidden="true" />
                <input
                  id="email-input"
                  type="email"
                  className="form-input"
                  placeholder="name@numm.gov.in"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  autoComplete="email"
                />
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="password-input" className="form-label">
                {t("auth.password")}
              </label>
              <div className="input-with-icon">
                <Lock size={16} className="input-icon" aria-hidden="true" />
                <input
                  id="password-input"
                  type="password"
                  className="form-input"
                  placeholder="••••••••"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  autoComplete="current-password"
                />
              </div>
            </div>

            <button
              type="submit"
              className="btn btn-primary btn-block btn-lg mt-3"
              disabled={loading}
              id="btn-login-submit"
              style={{
                background: "var(--accent)",
                color: "var(--surface)",
                border: "none",
                borderRadius: "var(--radius)",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                gap: "8px"
              }}
            >
              {loading ? t("auth.signingIn") : t("auth.signInBtn")}
              {!loading && <ArrowRight size={16} aria-hidden="true" />}
            </button>
          </form>

          {/* Rendered only when the demo-profile endpoint is available. */}
          {demoAccounts.length > 0 && <div className="demo-profiles-section">
            <div className="demo-divider">
              <span>
                Instant Demonstration Access
              </span>
            </div>

            <div className="demo-buttons-grid">
              {demoAccounts.map((acc) => (
                <button
                  key={acc.email}
                  type="button"
                  className="btn btn-demo"
                  onClick={() => handleDemoSignIn(acc.email, acc.password)}
                  disabled={loading}
                >
                  <div>
                    <div className="font-semibold text-sm">
                      {acc.label}
                    </div>
                    <div className="font-mono text-xs text-muted">
                      {acc.email} / {acc.password}
                    </div>
                  </div>
                  <span className="badge badge-accent">
                    {acc.role}
                  </span>
                </button>
              ))}
            </div>
          </div>}
        </section>
      </main>

      <footer className="login-footer">
        <span>National Unified Material Master · SIH 2026</span>
        <span>Secure · Auditable · Interoperable</span>
      </footer>
    </div>
  );
}
