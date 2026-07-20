import { Outlet, useLocation } from "react-router-dom";
import Navbar from "./Navbar";

export default function AppLayout() {
  const location = useLocation();
  const useDashboardShell = location.pathname.startsWith("/gov");
  const useCitizenShell = location.pathname.startsWith("/citizen");
  const useLandingShell = location.pathname === "/";
  const hideGlobalNavbar = useDashboardShell || useCitizenShell || useLandingShell;
  const mainClassName = useDashboardShell || useCitizenShell || useLandingShell
    ? "app-layout__main app-layout__main--flush"
    : "app-layout__main";

  return (
    <div className="app-layout">
      {!hideGlobalNavbar && <Navbar />}
      <main className={mainClassName}>
        <Outlet />
      </main>
    </div>
  );
}
