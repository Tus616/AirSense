import { BrowserRouter, Routes, Route } from "react-router-dom";
import AppLayout from "./components/AppLayout";
import ProtectedRoute from "./components/ProtectedRoute";
import LandingPage from "./pages/LandingPage";
import LoginPage from "./pages/LoginPage";
import SignupPage from "./pages/SignupPage";
import DecisionDashboard from "./pages/DecisionDashboard";
import CitizenDashboard from "./pages/CitizenDashboard";
import KioskDisplay from "./pages/KioskDisplay";
import "./App.css";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<LandingPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />

          <Route
            path="/gov/*"
            element={
              <ProtectedRoute allowedRole="ADMIN">
                <DecisionDashboard />
              </ProtectedRoute>
            }
          />

          <Route
            path="/citizen"
            element={
              <ProtectedRoute allowedRole="CITIZEN">
                <CitizenDashboard />
              </ProtectedRoute>
            }
          />
        </Route>

        <Route path="/kiosk/:wardId" element={<KioskDisplay />} />
      </Routes>
    </BrowserRouter>
  );
}