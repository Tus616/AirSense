import { Link, useNavigate } from "react-router-dom";
import { useDispatch } from "react-redux";
import { useAuth } from "../hooks/useAuth";
import { logout } from "../store/authSlice";

/**
 * Top navigation bar — adapts links based on auth state and role
 */
export default function Navbar() {
  const { isAuthenticated, user, role } = useAuth();
  const dispatch = useDispatch();
  const navigate = useNavigate();

  const handleLogout = () => {
    dispatch(logout());
    navigate("/");
  };

  return (
    <nav className="navbar">
      <Link to="/" className="navbar__brand">
        <span className="navbar__logo">🌬️</span>
        <span className="navbar__title">AirSense</span>
      </Link>

      <div className="navbar__links">
        {!isAuthenticated ? (
          <>
            <Link to="/" className="navbar__link">Home</Link>
            <Link to="/login" className="navbar__link navbar__link--cta">Login</Link>
          </>
        ) : (
          <>
            {role === "ADMIN" && (
              <Link to="/gov" className="navbar__link">Dashboard</Link>
            )}
            {role === "CITIZEN" && (
              <Link to="/citizen" className="navbar__link">Dashboard</Link>
            )}
            <div className="navbar__user">
              <span className="navbar__user-name">{user?.name}</span>
              <span className="navbar__user-role">{role}</span>
            </div>
            <button className="navbar__logout" onClick={handleLogout}>
              Logout
            </button>
          </>
        )}
      </div>
    </nav>
  );
}
