import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useSelector } from "react-redux";
import { motion } from "../motion/MotionPrimitives";

const roleRoutes = {
  ADMIN: "/gov",
  CITIZEN: "/citizen",
};

function roleForMode(mode) {
  return mode === "municipal" ? "ADMIN" : "CITIZEN";
}

export default function RoleSwitcher({ active = "municipal", compact = false }) {
  const navigate = useNavigate();
  const role = useSelector((state) => state.auth?.role);
  const [notice, setNotice] = useState("");

  function chooseMode(mode) {
    const requiredRole = roleForMode(mode);
    const route = roleRoutes[requiredRole];
    if (role && role !== requiredRole) {
      const label = mode === "municipal" ? "Municipal" : "Citizen";
      const ok = window.confirm(`${label} dashboard requires a ${label} account. Go to role selection to switch accounts?`);
      if (ok) navigate("/");
      setNotice(`${label} dashboard requires ${label} permissions.`);
      return;
    }
    setNotice("");
    navigate(route);
  }

  return (
    <div className={`role-switcher ${compact ? "is-compact" : ""}`} aria-label="Dashboard role switcher">
      <div className="role-switcher__control" role="tablist" aria-label="Municipal or citizen dashboard">
        {[
          ["municipal", "Municipal"],
          ["citizen", "Citizen"],
        ].map(([mode, label]) => (
          <motion.button
            key={mode}
            type="button"
            role="tab"
            aria-selected={active === mode}
            className={active === mode ? "is-active" : ""}
            onClick={() => chooseMode(mode)}
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.97 }}
          >
            {label}
          </motion.button>
        ))}
        <motion.span
          className={`role-switcher__indicator is-${active}`}
          aria-hidden="true"
          layout
          transition={{ type: "spring", stiffness: 420, damping: 34 }}
        />
      </div>
      {notice && <span className="role-switcher__notice" role="status">{notice}</span>}
    </div>
  );
}

export function BackToRoleSelection({ className = "" }) {
  return (
    <Link className={`role-back-link ${className}`.trim()} to="/">
      <span aria-hidden="true">←</span>
      Back to role selection
    </Link>
  );
}
