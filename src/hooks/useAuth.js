import { useSelector } from "react-redux";

/**
 * Convenience hook to access auth state from Redux store
 * @returns {{ user: Object|null, token: string|null, role: string|null, isAuthenticated: boolean }}
 */
export function useAuth() {
  return useSelector((state) => state.auth);
}
