import { useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { Link, useNavigate } from "react-router-dom";
import { loginAsync } from "../store/authSlice";

// Demo quick-login is disabled by default.
// To enable only in local development, set VITE_ENABLE_DEMO_LOGIN=true in your .env.
// Never expose passwords via VITE_ variables or source code.
const DEMO_LOGIN_ENABLED =
  import.meta.env.VITE_ENABLE_DEMO_LOGIN === "true";

/**
 * Login Page — API-driven authentication form.
 * Credentials are submitted to the backend; nothing is hardcoded here.
 */
export default function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const dispatch = useDispatch();
  const navigate = useNavigate();

  const { status, error } = useSelector((state) => state.auth);

  const handleSubmit = async (event) => {
    event.preventDefault();

    const resultAction = await dispatch(
      loginAsync({
        email,
        password,
      })
    );

    if (loginAsync.fulfilled.match(resultAction)) {
      const user = resultAction.payload.user;
      navigate(user.role === "ADMIN" ? "/gov" : "/citizen");
    }
  };

  const handleDemoFill = (role) => {
    const adminEmail =
      import.meta.env.VITE_DEMO_ADMIN_EMAIL ?? "";

    const citizenEmail =
      import.meta.env.VITE_DEMO_CITIZEN_EMAIL ?? "";

    setEmail(role === "ADMIN" ? adminEmail : citizenEmail);
    setPassword("");
  };

  return (
    <div className="login-page">
      <div className="login-page__card glass-card">
        <div className="login-page__header">
          <span className="login-page__icon">AI</span>
          <h1>Sign In</h1>
          <p>Access the AirSense Intelligence Platform</p>
        </div>

        <form
          className="login-page__form"
          onSubmit={handleSubmit}
        >
          <div className="form-group">
            <label htmlFor="email">Email Address</label>

            <input
              id="email"
              type="email"
              value={email}
              onChange={(event) =>
                setEmail(event.target.value)
              }
              placeholder="your-email@example.com"
              required
              autoComplete="username"
            />
          </div>

          <div className="form-group">
            <label htmlFor="password">Password</label>

            <input
              id="password"
              type="password"
              value={password}
              onChange={(event) =>
                setPassword(event.target.value)
              }
              placeholder="Enter your password"
              required
              autoComplete="current-password"
            />
          </div>

          {error && (
            <div className="login-page__error">
              {error}
            </div>
          )}

          <button
            type="submit"
            className="btn btn--primary btn--full"
            disabled={status === "loading"}
          >
            {status === "loading"
              ? "Signing In..."
              : "Sign In"}
          </button>
        </form>

        <p className="login-page__demo-note">
          Don&apos;t have an account?{" "}
          <Link to="/signup">Create account</Link>
        </p>

        {DEMO_LOGIN_ENABLED && (
          <>
            <div className="login-page__divider">
              <span>Fill Demo Email (dev only)</span>
            </div>

            <div className="login-page__quick-buttons">
              <button
                className="btn btn--outline btn--admin"
                onClick={() => handleDemoFill("ADMIN")}
                disabled={status === "loading"}
                type="button"
              >
                <span>ADM</span> Fill Admin Email
              </button>

              <button
                className="btn btn--outline btn--citizen"
                onClick={() => handleDemoFill("CITIZEN")}
                disabled={status === "loading"}
                type="button"
              >
                <span>CIT</span> Fill Citizen Email
              </button>
            </div>

            <p className="login-page__demo-note">
              Enter your password manually. Demo mode —
              not for production.
            </p>
          </>
        )}
      </div>
    </div>
  );
}