import { Navigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";

/**
 * Route guard that checks authentication and role
 * @param {{ allowedRole: string, children: React.ReactNode }} props
 */
export default function ProtectedRoute({ allowedRole, children }) {
  const { isAuthenticated, role } = useAuth();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (allowedRole && role !== allowedRole) {
    // Wrong role — redirect to their correct dashboard
    const redirect = role === "ADMIN" ? "/gov" : "/citizen";
    return <Navigate to={redirect} replace />;
  }

  return children;
}
