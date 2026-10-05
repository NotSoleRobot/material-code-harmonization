import { queryClient } from "../queryClient";

// Prefer the newest Blueprint-managed value. Some existing Render static-site
// environments still contain the retired FastAPI service URL; never allow that
// known legacy value to take precedence over the Spring API.
const SPRING_RENDER_ORIGIN = "https://numm-spring-api.onrender.com";
const LEGACY_RENDER_ORIGINS = new Set([
  "https://numm-backend.onrender.com",
]);
const configuredApiOrigin = (
  import.meta.env.VITE_API_BASE_URL_V3
  || import.meta.env.VITE_API_BASE_URL_V2
  || import.meta.env.VITE_API_BASE_URL
)?.trim().replace(/\/+$/, "");
const isHostedRenderFrontend = typeof window !== "undefined"
  && window.location.hostname === "numm-frontend.onrender.com";
const hostedApiOrigin = LEGACY_RENDER_ORIGINS.has(configuredApiOrigin)
  || (!configuredApiOrigin && isHostedRenderFrontend)
  ? SPRING_RENDER_ORIGIN
  : configuredApiOrigin;
const API_BASE = hostedApiOrigin ? `${hostedApiOrigin}/api` : "/api";

let _authToken = sessionStorage.getItem("numm_auth_token") || null;

export function setAuthToken(token) {
  _authToken = token;
  if (token) {
    sessionStorage.setItem("numm_auth_token", token);
  } else {
    sessionStorage.removeItem("numm_auth_token");
  }
}

export function getAuthToken() {
  return _authToken;
}

async function request(url, options = {}) {
  const { timeoutMs = 30000, ...fetchOptions } = options;
  const controller = new AbortController();
  const externalAbort = () => controller.abort();
  if (fetchOptions.signal) {
    if (fetchOptions.signal.aborted) controller.abort();
    else fetchOptions.signal.addEventListener("abort", externalAbort, { once: true });
  }
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  const headers = {
    "Content-Type": "application/json",
    ...(fetchOptions.headers || {}),
  };

  if (_authToken) {
    headers["Authorization"] = `Bearer ${_authToken}`;
  }

  let res;
  try {
    res = await fetch(url, {
      ...fetchOptions,
      headers,
      signal: controller.signal,
    });
  } catch (error) {
    const isTimeout = error?.name === "AbortError";
    const networkError = new Error(
      isTimeout
        ? "The NUMM service took too long to respond. Please retry in a moment."
        : "Cannot reach the NUMM service. Confirm that the backend is running, then retry."
    );
    networkError.status = 0;
    networkError.code = isTimeout ? "REQUEST_TIMEOUT" : "NETWORK_ERROR";
    throw networkError;
  } finally {
    clearTimeout(timeoutId);
    fetchOptions.signal?.removeEventListener("abort", externalAbort);
  }

  if (res.status === 401) {
    if (!url.includes("/api/auth/login")) {
      setAuthToken(null);
      queryClient.clear();
      window.dispatchEvent(new CustomEvent("numm:unauthorized"));
    }
    const errorBody = await res.json().catch(() => ({}));
    throw new Error(errorBody.message || "Unauthorized: Session expired or invalid credentials.");
  }

  if (!res.ok) {
    const errorBody = await res.json().catch(() => ({}));
    const serviceUnavailable = [502, 503, 504].includes(res.status);
    const message = serviceUnavailable
      ? (errorBody.message || "A required NUMM service is temporarily unavailable. Please retry after the backend and matching service are healthy.")
      : errorBody.message || errorBody.error || `HTTP ${res.status}: ${res.statusText}`;
    const err = new Error(message);
    err.status = res.status;
    err.code = errorBody.error || (serviceUnavailable ? "SERVICE_UNAVAILABLE" : "HTTP_ERROR");
    err.details = errorBody;
    throw err;
  }

  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  // --- Authentication ---
  async login(email, password) {
    const data = await request(`${API_BASE}/auth/login`, {
      method: "POST",
      body: JSON.stringify({ email, password }),
      // The hosted Spring service may need to resume together with its
      // free-tier database. Do not turn a legitimate cold start into a
      // misleading authentication failure.
      timeoutMs: 180000,
    });
    if (data && data.accessToken) {
      setAuthToken(data.accessToken);
    }
    return data;
  },

  async getCurrentUser() {
    return request(`${API_BASE}/auth/me`);
  },

  async getDemoAccounts() {
    try {
      return await request(`${API_BASE}/auth/demo-accounts`, { timeoutMs: 180000 });
    } catch (error) {
      if (error.status === 404) return [];
      throw error;
    }
  },

  async logout() {
    try {
      await request(`${API_BASE}/auth/logout`, { method: "POST" });
    } finally {
      setAuthToken(null);
    }
  },

  // --- Dashboard and analytics ---
  async getDashboardStats() {
    return request(`${API_BASE}/dashboard/stats`);
  },

  async getRateContractCandidates() {
    return request(`${API_BASE}/analytics/rate-contract-candidates`);
  },

  async getPriceVariance() {
    return request(`${API_BASE}/analytics/price-variance`);
  },

  async getAssumptions() {
    return request(`${API_BASE}/analytics/assumptions`);
  },

  async updateAssumption(key, data) {
    return request(`${API_BASE}/analytics/assumptions/${key}`, {
      method: "PUT",
      body: JSON.stringify(data),
    });
  },

  // --- Codes and national catalog ---
  async getCodeDetails(code) {
    return request(`${API_BASE}/codes/${encodeURIComponent(code)}`);
  },

  async validateCode(code) {
    return request(`${API_BASE}/codes/${encodeURIComponent(code)}/validate`);
  },

  async searchCodes(query = "", status = "ACTIVE", category = "ALL", page = 0, size = 20) {
    let url = `${API_BASE}/codes/search?q=${encodeURIComponent(query)}&page=${page}&size=${size}`;
    if (status) {
      url += `&status=${encodeURIComponent(status)}`;
    }
    if (category && category !== "ALL") {
      url += `&category=${encodeURIComponent(category)}`;
    }
    const response = await request(url);
    if (Array.isArray(response)) {
      return {
        content: response,
        number: 0,
        size: response.length,
        totalElements: response.length,
        totalPages: response.length > 0 ? 1 : 0,
        first: true,
        last: true,
      };
    }
    return response;
  },

  async getCatalogCategories() {
    return request(`${API_BASE}/codes/categories`);
  },

  // --- Review queue and governance ---
  async getMappings(status = "PENDING", categoryId = null) {
    let url = `${API_BASE}/mappings?status=${status}`;
    if (categoryId) url += `&categoryId=${categoryId}`;
    const response = await request(url);
    return Array.isArray(response) ? response : (response?.content || []);
  },

  async getMappingsPage(status = "PENDING", page = 0, size = 50, categoryId = null) {
    let url = `${API_BASE}/mappings?status=${status}&page=${page}&size=${size}`;
    if (categoryId) url += `&categoryId=${categoryId}`;
    const response = await request(url);
    return Array.isArray(response)
      ? { content: response, page: 0, size: response.length, totalElements: response.length, totalPages: 1 }
      : response;
  },

  async getMappingById(mappingId) {
    return request(`${API_BASE}/mappings/${mappingId}`);
  },

  async approveMapping(mappingId, notes = "Verified attributes match") {
    return request(`${API_BASE}/mappings/${mappingId}/approve`, {
      method: "POST",
      body: JSON.stringify({ notes }),
    });
  },

  async approveAndPublish(mappingId) {
    return request(`${API_BASE}/mappings/${mappingId}/approve-and-publish`, {
      method: "POST",
      timeoutMs: 120000,
    });
  },

  async rejectMapping(mappingId, notes = "Specification mismatch") {
    return request(`${API_BASE}/mappings/${mappingId}/reject`, {
      method: "POST",
      body: JSON.stringify({ notes }),
    });
  },

  async editMapping(mappingId, data) {
    return request(`${API_BASE}/mappings/${mappingId}/edit`, {
      method: "POST",
      body: JSON.stringify(data),
    });
  },

  async bulkApproveHighConfidence(categoryId = null) {
    const url = categoryId
      ? `${API_BASE}/mappings/bulk-approve?categoryId=${categoryId}`
      : `${API_BASE}/mappings/bulk-approve`;
    return request(url, { method: "POST" });
  },

  async supersedeMapping(mappingId, newDecision, reason) {
    return request(`${API_BASE}/mappings/${mappingId}/supersede`, {
      method: "POST",
      body: JSON.stringify({ newDecision, reason }),
    });
  },

  // --- Publication and code minting ---
  async getPublishableGroups() {
    return request(`${API_BASE}/groups/publishable`);
  },

  async publishGroup(groupId) {
    return request(`${API_BASE}/groups/${groupId}/mint`, {
      method: "POST",
    });
  },

  // --- Materials and ingestion ---
  async getMaterials() {
    return request(`${API_BASE}/materials`);
  },

  async getMaterial(id) {
    return request(`${API_BASE}/materials/${id}`);
  },

  async createMaterial(payload) {
    return request(`${API_BASE}/materials`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  async requestNewCode(payload) {
    return request(`${API_BASE}/materials/request-code`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  async uploadBulkMaterials(csvText, autoHarmonize = true) {
    return request(`${API_BASE}/materials/bulk-csv?autoHarmonize=${autoHarmonize}`, {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: csvText,
      timeoutMs: 180000,
    });
  },

  async getJobStatus(jobId) {
    return request(`${API_BASE}/jobs/${jobId}`);
  },

  streamJobEvents(jobId, onMessage, onError) {
    if (typeof window === "undefined" || !window.EventSource) {
      return null;
    }
    const token = getAuthToken();
    const url = `${API_BASE}/jobs/${jobId}/events${token ? `?token=${encodeURIComponent(token)}` : ""}`;
    const source = new EventSource(url);

    source.addEventListener("job-status", (event) => {
      try {
        const data = JSON.parse(event.data);
        onMessage?.(data);
      } catch (err) {
        console.warn("Failed to parse SSE job status payload:", err);
      }
    });

    source.onerror = (err) => {
      source.close();
      onError?.(err);
    };

    return () => {
      source.close();
    };
  },

  // --- Material comparison and harmonization ---
  async compareMaterials(materialA, materialB) {
    return request(`${API_BASE}/harmonization/compare`, {
      method: "POST",
      body: JSON.stringify({ material_a: materialA, material_b: materialB }),
      // The hosted matching worker may be resuming from Render's free-tier
      // sleep. Keep the comparison request alive while the backend retries.
      timeoutMs: 540000,
    });
  },

  async harmonizeMaterial(materialId) {
    return request(`${API_BASE}/harmonization/material/${materialId}`, { method: "POST", timeoutMs: 120000 });
  },

  async harmonizeAll() {
    return request(`${API_BASE}/harmonization/harmonize-all`, { method: "POST", timeoutMs: 120000 });
  },

  // --- Audit trail and verification ---
  async getAuditTrail(action = "") {
    const url = action ? `${API_BASE}/mappings/audit?action=${encodeURIComponent(action)}` : `${API_BASE}/mappings/audit`;
    return request(url);
  },

  async verifyAuditChain() {
    return request(`${API_BASE}/mappings/audit/verify`);
  },

  async tamperAuditRow(auditId) {
    return request(`${API_BASE}/demo/tamper-audit/${auditId}`, { method: "POST" });
  },

  // --- User administration ---
  async getAdminUsers() {
    return request(`${API_BASE}/admin/users`);
  },

  async getAdminUserOptions() {
    return request(`${API_BASE}/admin/users/options`);
  },

  async createAdminUser(userData) {
    return request(`${API_BASE}/admin/users`, {
      method: "POST",
      body: JSON.stringify(userData),
    });
  },

  // --- Export downloads ---
  async download(path, filename) {
    const headers = {};
    if (_authToken) headers["Authorization"] = `Bearer ${_authToken}`;
    let downloadUrl;
    try {
      const res = await fetch(path, { headers });
      if (!res.ok) {
        const errBody = await res.json().catch(() => ({}));
        throw new Error(errBody.message || `Export failed with status ${res.status}`);
      }
      downloadUrl = window.URL.createObjectURL(await res.blob());
      const a = document.createElement("a");
      a.href = downloadUrl;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
    } finally {
      if (downloadUrl) window.URL.revokeObjectURL(downloadUrl);
    }
  },

  async downloadExport(type, format = "csv") {
    let path = "";
    if (type === "erp") {
      path = `${API_BASE}/export/erp-template?format=csv`;
    } else if (type === "master") {
      path = `${API_BASE}/export/catalog?format=${format}`;
    } else {
      path = `${API_BASE}/export/cross-reference?format=${format}`;
    }

    const ext = format === "xlsx" ? "xlsx" : "csv";
    return this.download(path, `NUMM_${type}_export.${ext}`);
  },
};
