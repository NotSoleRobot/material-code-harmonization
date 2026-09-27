import React, { useState, useEffect, useCallback } from "react";
import { api, getAuthToken, setAuthToken } from "../services/api";
import { queryClient } from "../queryClient";
import { AuthContext } from "./auth-context";

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [token, setTokenState] = useState(getAuthToken());
  const [isLoading, setIsLoading] = useState(true);

  // Restore user session on mount
  useEffect(() => {
    async function restoreSession() {
      const storedToken = getAuthToken();
      if (storedToken) {
        try {
          const profile = await api.getCurrentUser();
          setUser(profile);
          setTokenState(storedToken);
        } catch (err) {
          console.warn("Session expired or invalid:", err.message);
          setAuthToken(null);
          setTokenState(null);
          setUser(null);
        }
      }
      setIsLoading(false);
    }

    restoreSession();

    const handleUnauthorized = () => {
      queryClient.clear();
      setTokenState(null);
      setUser(null);
    };

    window.addEventListener("numm:unauthorized", handleUnauthorized);
    return () => window.removeEventListener("numm:unauthorized", handleUnauthorized);
  }, []);

  const login = useCallback(async (email, password) => {
    const data = await api.login(email, password);
    setTokenState(data.accessToken);
    setUser(data.user);
    return data;
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } catch {
      // ignore network errors on logout
    } finally {
      queryClient.clear();
      setTokenState(null);
      setUser(null);
    }
  }, []);

  const hasRole = useCallback((allowedRoles) => {
    if (!user || !user.role) return false;
    if (Array.isArray(allowedRoles)) {
      return allowedRoles.includes(user.role);
    }
    return user.role === allowedRoles;
  }, [user]);

  const value = {
    user,
    token,
    isAuthenticated: !!user && !!token,
    isLoading,
    login,
    logout,
    hasRole,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
