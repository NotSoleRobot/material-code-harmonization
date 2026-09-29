import { queryClient } from "../queryClient";

// Prefer the Blueprint-managed V2 value. The original variable may still hold
// a manually entered URL from an older Render service.
const hostedApiOrigin = (
  import.meta.env.VITE_API_BASE_URL_V2 || import.meta.env.VITE_API_BASE_URL
)?.trim().replace(/\/+$/, "");
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
      return await request(`${API_BASE}/auth/demo-accounts`);
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

  async searchCodes(query = "", status = "ACTIVE") {
    let url = `${API_BASE}/codes/search?q=${encodeURIComponent(query)}`;
    if (status) {
      url += `&status=${encodeURIComponent(status)}`;
    }
    return request(url);
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

  // --- Material comparison and harmonization ---
  async compareMaterials(materialA, materialB) {
    return request(`${API_BASE}/harmonization/compare`, {
      method: "POST",
      body: JSON.stringify({ material_a: materialA, material_b: materialB }),
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
