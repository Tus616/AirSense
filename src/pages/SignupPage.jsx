import { useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { Link, useNavigate } from "react-router-dom";
import { registerAsync } from "../store/authSlice";

export default function SignupPage() {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [localError, setLocalError] = useState("");

  const dispatch = useDispatch();
  const navigate = useNavigate();
  const { status, error } = useSelector((state) => state.auth);

  const handleSubmit = async (event) => {
    event.preventDefault();
    setLocalError("");

    if (password.length < 8) {
      setLocalError("Password must be at least 8 characters");
      return;
    }

    if (password !== confirmPassword) {
      setLocalError("Passwords do not match");
      return;
    }

    const resultAction = await dispatch(
      registerAsync({
        name,
        email,
        password,
      })
    );

    if (registerAsync.fulfilled.match(resultAction)) {
      navigate("/citizen");
    }
  };

  return (
    <div className="login-page">
      <div className="login-page__card glass-card">
        <div className="login-page__header">
          <span className="login-page__icon">AI</span>
          <h1>Create Account</h1>
          <p>Join the AirSense Intelligence Platform</p>
        </div>

        <form className="login-page__form" onSubmit={handleSubmit}>
          <div className="form-group">
            <label htmlFor="name">Full Name</label>
            <input
              id="name"
              type="text"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Enter your full name"
              required
              autoComplete="name"
            />
          </div>

          <div className="form-group">
            <label htmlFor="signup-email">Email Address</label>
            <input
              id="signup-email"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="your-email@example.com"
              required
              autoComplete="email"
            />
          </div>

          <div className="form-group">
            <label htmlFor="signup-password">Password</label>
            <input
              id="signup-password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="Minimum 8 characters"
              required
              minLength={8}
              autoComplete="new-password"
            />
          </div>

          <div className="form-group">
            <label htmlFor="confirm-password">Confirm Password</label>
            <input
              id="confirm-password"
              type="password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              placeholder="Re-enter your password"
              required
              minLength={8}
              autoComplete="new-password"
            />
          </div>

          {(localError || error) && (
            <div className="login-page__error">
              {localError || error}
            </div>
          )}

          <button
            type="submit"
            className="btn btn--primary btn--full"
            disabled={status === "loading"}
          >
            {status === "loading" ? "Creating Account..." : "Create Account"}
          </button>
        </form>

        <p className="login-page__demo-note">
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </div>
    </div>
  );
}