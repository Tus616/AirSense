import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { useDispatch, useSelector } from "react-redux";
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import CitySelector from "../components/decision/CitySelector";
import GisDecisionMap from "../components/decision/GisDecisionMap";
import TimelineSlider from "../components/decision/TimelineSlider";
import RoleSwitcher, { BackToRoleSelection } from "../components/layout/RoleSwitcher";
import { AnimatedNumber, MotionCard, MotionPage, motion, staggerContainer } from "../components/motion/MotionPrimitives";
import { ErrorState, PanelSkeleton, RiskOverviewSkeleton } from "../components/decision/StatusViews";
import {
  getDecisionIntelligence,
  getHistoricalReplayStations,
  getTemporalTimeline,
  queryDecisionCopilot,
  runHistoricalReplay,
} from "../services/decisionApi";
import { setSelectedCity } from "../store/uiStateSlice";
import { logout } from "../store/authSlice";
import {
  asArray,
  asNumber,
  formatPercent,
  getAqiTone,
  getForecastPoints,
  labelize,
  toDisplayText,
} from "../components/decision/decisionUtils";

const NAV_ITEMS = [
  { id: "overview", label: "Command Center", path: "/gov", code: "OV" },
  { id: "forecast", label: "Forecast", path: "/gov/forecast", code: "FC" },
  { id: "stations", label: "Stations", path: "/gov/stations", code: "ST" },
  { id: "maps", label: "Maps", path: "/gov/maps", code: "MP" },
  { id: "source-analysis", label: "Source Analysis", path: "/gov/source-analysis", code: "SA" },
  { id: "historical-replay", label: "Historical Replay", path: "/gov/historical-replay", code: "HR" },
  { id: "enforcement", label: "Enforcement", path: "/gov/enforcement", code: "EN" },
  { id: "health-advisory", label: "Health Advisory", path: "/gov/health-advisory", code: "HA" },
  { id: "alerts", label: "Alerts", path: "/gov/alerts", code: "AL" },
  { id: "reports", label: "Reports", path: "/gov/reports", code: "RP" },
  { id: "data-explorer", label: "Data Explorer", path: "/gov/data-explorer", code: "DX" },
  { id: "settings", label: "Settings", path: "/gov/settings", code: "SE" },
];

const NAV_GROUPS = [
  ["Overview", ["overview", "alerts"]],
  ["Monitoring", ["maps", "stations", "forecast"]],
  ["Analytics", ["source-analysis", "historical-replay", "reports", "data-explorer"]],
  ["Enforcement", ["enforcement", "health-advisory"]],
  ["Management", ["settings"]],
];

const VERIFIED_REPLAY_STATIONS = [
  "Gomti Nagar, Lucknow",
  "ITO, Delhi",
  "BKC, Mumbai",
];

const CITY_PRESETS = [
  {
    cityId: "LUCKNOW",
    cityName: "Lucknow",
    displayName: "Lucknow, Uttar Pradesh, India",
    latitude: 26.8467,
    longitude: 80.9462,
    state: "Uttar Pradesh",
    country: "India",
  },
  {
    cityId: "DELHI",
    cityName: "Delhi",
    displayName: "Delhi, India",
    latitude: 28.6139,
    longitude: 77.209,
    state: "Delhi",
    country: "India",
  },
  {
    cityId: "MUMBAI",
    cityName: "Mumbai",
    displayName: "Mumbai, Maharashtra, India",
    latitude: 19.076,
    longitude: 72.8777,
    state: "Maharashtra",
    country: "India",
  },
];

function activePageFromPath(pathname) {
  const segment = pathname.replace(/^\/gov\/?/, "").split("/")[0] || "overview";
  return NAV_ITEMS.some((item) => item.id === segment) ? segment : "overview";
}

function formatDateTime(value, fallback = "Unavailable") {
  if (!value) return fallback;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return toDisplayText(value, fallback);
  return date.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

function dateInputValue(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toISOString().slice(0, 10);
}

function timeInputValue(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toISOString().slice(11, 16);
}

function formatAqi(value, fallback = "Unavailable") {
  if (value === null || value === undefined || value === "") return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? String(Math.round(parsed)) : fallback;
}

function confidenceText(value) {
  if (value === null || value === undefined || value === "") return "Unavailable";
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return toDisplayText(value);
  return numeric > 1 ? `${Math.round(numeric)}%` : formatPercent(numeric);
}

function fallbackReasonLabel(reason) {
  const labels = {
    MODEL_NOT_PROMOTED: "Model not promoted",
    ARTIFACT_UNAVAILABLE: "Model artifact unavailable",
    ML_SERVICE_UNAVAILABLE: "ML service unavailable",
    FEATURE_SCHEMA_MISMATCH: "Feature schema mismatch",
    INSUFFICIENT_CONTIGUOUS_LIVE_HISTORY: "Insufficient contiguous live history",
    LIVE_HISTORY_STALE: "Live history is stale",
    LIVE_HISTORY_COVERAGE_LOW: "Live history coverage is low",
    LIVE_HISTORY_GAP_TOO_LARGE: "Live history gap is too large",
    CHECKSUM_MISMATCH: "Model checksum mismatch",
    FALLBACK_ENGINE_USED: "Fallback engine used",
    EXTREME_FORECAST_CHANGE: "Extreme forecast change",
    OUT_OF_DISTRIBUTION_FEATURES: "Out-of-distribution features",
    LOW_GENERALIZATION_CONFIDENCE: "Low generalization confidence",
    CHRONOS_DISABLED: "Pretrained model disabled",
    CHRONOS_UNAVAILABLE: "Pretrained model unavailable",
    INSUFFICIENT_HISTORY_FOR_CHRONOS: "Insufficient history for pretrained model",
    PROVIDER_FORECAST_UNAVAILABLE: "Provider forecast unavailable",
    PROVIDER_FORECAST_STANDARD_MISMATCH: "Provider forecast uses a different AQI standard",
  };
  return String(reason || "")
    .split(";")
    .filter(Boolean)
    .map((part) => labels[part] || labelize(part, "No fallback reason reported"))
    .join(" / ");
}

function hasFallbackDiagnostics(point) {
  const mode = point?.engine || point?.mode || "";
  return mode === "PERSISTENCE" || mode === "PERSISTENCE_FALLBACK" || mode === "UNAVAILABLE"
    || asArray(point?.insufficiencyReasons).length > 0;
}

function advisoryAudienceKey(item) {
  const value = toDisplayText(
    item?.audience || item?.vulnerableGroup || item?.group || item?.targetGroup || item?.population,
    "General population",
  ).toLowerCase();
  if (value.includes("child") || value.includes("school")) return "Children";
  if (value.includes("elder") || value.includes("senior")) return "Elderly";
  if (value.includes("asthma") || value.includes("copd") || value.includes("respiratory")) return "Asthma/COPD";
  if (value.includes("pregnan")) return "Pregnant women";
  if (value.includes("worker") || value.includes("outdoor")) return "Outdoor workers";
  if (value.includes("general") || value === "unavailable") return "General population";
  return labelize(value, "General population");
}

function splitEvidenceText(value) {
  if (value == null) return [];
  if (typeof value === "object") return [value];
  return String(value)
    .split(/\n|\|/)
    .map((line) => line.trim())
    .filter(Boolean)
    .flatMap((line) => line.includes(";") ? [line] : [line]);
}

function parseEvidence(value) {
  return asArray(value).flatMap((entry) => {
    if (entry && typeof entry === "object" && !Array.isArray(entry)) {
      return [{
        dataset: entry.dataset || entry.source || entry.collection,
        signal: entry.signal || entry.field || entry.metric,
        description: entry.description || entry.reason || entry.summary || entry.evidence,
        confidence: entry.confidence,
      }];
    }
    return splitEvidenceText(entry).map((line) => {
      const row = {};
      String(line).split(";").forEach((part) => {
        const [rawKey, ...rawValue] = part.split(":");
        if (!rawKey || rawValue.length === 0) return;
        row[rawKey.trim().toLowerCase()] = rawValue.join(":").trim();
      });
      return Object.keys(row).length > 0 ? row : { description: line };
    });
  });
}

function advisoryTitle(item, audience) {
  return toDisplayText(item?.title || item?.headline || item?.name, `${audience} advisory`);
}

function advisoryGuidance(item) {
  return toDisplayText(
    item?.guidance || item?.recommendation || item?.action || item?.message || item?.description,
    "No public guidance text was returned for this advisory.",
  );
}

function actionDetailText(item) {
  const detail = item?.description || item?.reason || item?.status || item?.message;
  if (detail) return toDisplayText(detail);
  if (item?.evidence || item?.technicalEvidence || item?.supportingEvidence) {
    return "Technical evidence is available in the relevant detail view.";
  }
  return "No additional detail returned.";
}

function engineLabel(point, forecastResult) {
  const mode = point?.engine || point?.mode || forecastResult?.engine || forecastResult?.mode || point?.modelFamily || "UNAVAILABLE";
  if (mode === "CHRONOS_BOLT_ZERO_SHOT") return "Pretrained AI Forecast";
  if (mode === "OPEN_METEO_PROVIDER_FORECAST") return "Atmospheric Provider Forecast";
  if (mode === "PERSISTENCE_FALLBACK") return "Persistence Fallback";
  if (mode === "UNAVAILABLE") return "Forecast Unavailable";
  if (mode.startsWith("ML_") || point?.modelPromotionStatus === "PROMOTED" || point?.promotionStatus === "PROMOTED") {
    return "Validated ML Model";
  }
  if (mode === "TREND_WEATHER_V1") return "Trend + Weather";
  if (mode === "PERSISTENCE" || mode === "PERSISTENCE_FALLBACK") return "Persistence Fallback";
  if (mode === "UNAVAILABLE" || point?.predictedAqi == null) return "Unavailable";
  return labelize(mode);
}

function stationNameFromDecision(decision) {
  return decision?.forecast?.stationName
    || decision?.forecast?.forecasts?.find((point) => point?.stationName)?.stationName
    || decision?.environmentalSignals?.stationName
    || "Station unavailable";
}

function shortCityName(city) {
  return city?.cityName || city?.name || city?.displayName?.split(",")[0] || "Selected city";
}

function commandTitle(activePage, city) {
  const label = NAV_ITEMS.find((item) => item.id === activePage)?.label || "Command Center";
  if (!city?.cityName && !city?.displayName) return label;
  return activePage === "overview" ? `${shortCityName(city)} Command Center` : label;
}

function commandSubtitle(activePage) {
  if (activePage === "overview") return "Municipal air-quality intelligence";
  if (activePage === "historical-replay") return "Archive-backed forecast validation";
  return "Operational air-quality intelligence";
}

function useDecisionData(selectedCityMetadata) {
  const [decision, setDecision] = useState(null);
  const [timeline, setTimeline] = useState(null);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineError, setTimelineError] = useState("");
  const [selectedFrameIndex, setSelectedFrameIndex] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const decisionRequestRef = useRef(null);
  const timelineRequestRef = useRef(null);
  const requestSeqRef = useRef(0);

  const fetchDecision = useCallback(async () => {
    const hasCoordinates = Number.isFinite(Number(selectedCityMetadata?.latitude))
      && Number.isFinite(Number(selectedCityMetadata?.longitude));
    if (!hasCoordinates) {
      if (decisionRequestRef.current) decisionRequestRef.current.abort();
      if (timelineRequestRef.current) timelineRequestRef.current.abort();
      setDecision(null);
      setTimeline(null);
      setTimelineError("");
      setError("");
      setLoading(false);
      setTimelineLoading(false);
      return;
    }

    if (decisionRequestRef.current) decisionRequestRef.current.abort();
    if (timelineRequestRef.current) timelineRequestRef.current.abort();

    const requestId = requestSeqRef.current + 1;
    requestSeqRef.current = requestId;
    const controller = new AbortController();
    decisionRequestRef.current = controller;
    setLoading(true);
    setError("");
    setDecision(null);
    setTimeline(null);
    setTimelineError("");
    setTimelineLoading(false);
    setSelectedFrameIndex(1);

    try {
      const data = await getDecisionIntelligence(selectedCityMetadata, { signal: controller.signal });
      if (requestSeqRef.current !== requestId) return;
      setDecision(data && typeof data === "object" ? data : null);
      setLoading(false);

      const timelineController = new AbortController();
      timelineRequestRef.current = timelineController;
      setTimelineLoading(true);
      try {
        const timelineData = await getTemporalTimeline(selectedCityMetadata, { signal: timelineController.signal });
        if (requestSeqRef.current !== requestId) return;
        const frames = asArray(timelineData?.frames);
        setTimeline(timelineData && typeof timelineData === "object" ? timelineData : null);
        setSelectedFrameIndex(frames.length > 1 ? 1 : 0);
      } catch (timelineErr) {
        if (timelineErr?.name === "CanceledError" || timelineErr?.code === "ERR_CANCELED") return;
        if (requestSeqRef.current !== requestId) return;
        setTimeline(null);
        setTimelineError(timelineErr?.response?.data?.message || timelineErr?.message || "Timeline API request failed.");
      } finally {
        if (timelineRequestRef.current === timelineController) {
          timelineRequestRef.current = null;
          setTimelineLoading(false);
        }
      }
    } catch (err) {
      if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
      if (requestSeqRef.current !== requestId) return;
      setDecision(null);
      setError(err?.response?.data?.message || err?.message || "Decision API request failed.");
    } finally {
      if (decisionRequestRef.current === controller) {
        decisionRequestRef.current = null;
        setLoading(false);
      }
    }
  }, [selectedCityMetadata]);

  useEffect(() => {
    fetchDecision();
    return () => {
      if (decisionRequestRef.current) decisionRequestRef.current.abort();
      if (timelineRequestRef.current) timelineRequestRef.current.abort();
    };
  }, [fetchDecision]);

  return {
    decision,
    timeline,
    timelineLoading,
    timelineError,
    selectedFrameIndex,
    setSelectedFrameIndex,
    loading,
    error,
    refresh: fetchDecision,
  };
}

function initialThemeMode() {
  if (typeof window === "undefined") return "light";
  const stored = window.localStorage.getItem("airsense-theme");
  if (stored === "dark" || stored === "light") return stored;
  return window.matchMedia?.("(prefers-color-scheme: dark)")?.matches ? "dark" : "light";
}

function useThemeMode() {
  const [theme, setTheme] = useState(initialThemeMode);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    window.localStorage.setItem("airsense-theme", theme);
  }, [theme]);

  return [theme, setTheme];
}

export default function DecisionDashboard() {
  const dispatch = useDispatch();
  const navigate = useNavigate();
  const location = useLocation();
  const selectedCity = useSelector((state) => state.uiState.selectedCity);
  const selectedCityMetadata = useSelector((state) => state.uiState.selectedCityMetadata);
  const authUser = useSelector((state) => state.auth?.user);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [selectedStation, setSelectedStation] = useState("");
  const [theme, setTheme] = useThemeMode();
  const pageId = activePageFromPath(location.pathname);
  const mapsReady = true;
  const mapsLoadError = null;

  const data = useDecisionData(selectedCityMetadata);
  const timelineFrames = asArray(data.timeline?.frames);
  const activeFrame = timelineFrames[data.selectedFrameIndex] || null;
  const stationName = stationNameFromDecision(data.decision);
  const generatedAt = formatDateTime(data.decision?.generatedAt, "Pending");
  const stationOptions = useMemo(() => {
    const options = new Set([stationName, ...VERIFIED_REPLAY_STATIONS]);
    return Array.from(options).filter((item) => item && item !== "Station unavailable");
  }, [stationName]);

  useEffect(() => {
    if (!selectedStation && stationName !== "Station unavailable") {
      setSelectedStation(stationName);
    }
  }, [selectedStation, stationName]);

  useEffect(() => {
    setSidebarOpen(false);
  }, [location.pathname]);

  const context = {
    ...data,
    city: selectedCityMetadata,
    selectedCity,
    activeFrame,
    timelineFrames,
    mapsReady,
    mapsLoadError,
    selectedStation,
    stationOptions,
    generatedAt,
  };

  return (
    <div className={`uqi-shell ${sidebarCollapsed ? "is-collapsed" : ""}`}>
      <DashboardSidebar
        activePage={pageId}
        open={sidebarOpen}
        collapsed={sidebarCollapsed}
        onClose={() => setSidebarOpen(false)}
        onToggleCollapse={() => setSidebarCollapsed((value) => !value)}
        decision={data.decision}
        generatedAt={generatedAt}
      />

      <div className="uqi-workspace">
        <DashboardHeader
          activePage={pageId}
          city={selectedCityMetadata}
          loading={data.loading}
          error={data.error}
          mapsLoadError={mapsLoadError}
          stationOptions={stationOptions}
          selectedStation={selectedStation}
          onStationChange={setSelectedStation}
          onMenu={() => setSidebarOpen(true)}
          onRefresh={data.refresh}
          onCityChange={(city) => dispatch(setSelectedCity(city))}
          onSettings={() => navigate("/gov/settings")}
          onLogout={() => {
            dispatch(logout());
            navigate("/login");
          }}
          user={authUser}
          generatedAt={generatedAt}
          decision={data.decision}
          theme={theme}
          onThemeChange={setTheme}
        />

        <MotionPage className={`uqi-page uqi-page--${pageId}`} aria-live="polite">
          {data.loading && <CoreLoadingView />}
          {!data.loading && data.error && <ErrorState message={data.error} onRetry={data.refresh} />}
          {!data.loading && !data.error && !data.decision && (
            <PlaceRequiredView onSelectCity={(city) => dispatch(setSelectedCity(city))} />
          )}
          {!data.loading && !data.error && data.decision && (
            <Routes>
              <Route path="/" element={<OverviewPage context={context} />} />
              <Route path="/forecast" element={<ForecastPage context={context} />} />
              <Route path="/historical-replay" element={<HistoricalReplayPage />} />
              <Route path="/stations" element={<StationsPage context={context} />} />
              <Route path="/maps" element={<MapsPage context={context} />} />
              <Route path="/source-analysis" element={<SourceAnalysisPage context={context} />} />
              <Route path="/enforcement" element={<EnforcementPage context={context} />} />
              <Route path="/health-advisory" element={<HealthAdvisoryPage context={context} />} />
              <Route path="/alerts" element={<AlertsPage context={context} />} />
              <Route path="/reports" element={<ReportsPage context={context} />} />
              <Route path="/data-explorer" element={<DataExplorerPage context={context} />} />
              <Route path="/settings" element={<SettingsPage context={context} />} />
              <Route path="*" element={<Navigate to="/gov" replace />} />
            </Routes>
          )}
        </MotionPage>
      </div>

      {!data.loading && data.decision && (
        <DecisionCopilotDrawer
          cityId={data.decision?.cityId || selectedCity}
          city={selectedCityMetadata}
          timelineFrame={activeFrame?.label || null}
          degradedMode={Boolean(data.decision?.engineStatus?.degradedMode)}
          suggestedQuestions={data.decision?.suggestedQuestions}
          snapshotId={data.decision?.snapshotId}
          compactLauncher={pageId === "overview"}
        />
      )}
    </div>
  );
}

function DashboardSidebar({ activePage, open, collapsed, onClose, onToggleCollapse, decision, generatedAt }) {
  const provider = decision?.forecast?.currentProvider || decision?.environmentalSignals?.provider || "Provider unavailable";
  const standard = decision?.forecast?.forecastStandard || decision?.environmentalSignals?.aqiStandard || "AQI standard unavailable";
  const itemLookup = useMemo(() => Object.fromEntries(NAV_ITEMS.map((item) => [item.id, item])), []);

  return (
    <>
      <aside className={`uqi-sidebar ${open ? "is-open" : ""}`} aria-label="Government workspace navigation">
        <div className="uqi-sidebar__brand">
          <div className="uqi-brand-mark" aria-hidden="true">AQ</div>
          {!collapsed && (
            <div>
              <strong>AirSense</strong>
              <span>Command Center</span>
            </div>
          )}
          <button className="uqi-icon-button uqi-sidebar__close" type="button" onClick={onClose} aria-label="Close navigation">x</button>
        </div>

        <nav className="uqi-nav">
          {NAV_GROUPS.map(([group, ids]) => (
            <div className="uqi-nav__group" key={group}>
              {!collapsed && <span className="uqi-nav__heading">{group}</span>}
              {ids.map((id) => itemLookup[id]).filter(Boolean).map((item) => (
                <Link
                  key={item.id}
                  className={`uqi-nav__item ${activePage === item.id ? "is-active" : ""}`}
                  to={item.path}
                  title={collapsed ? item.label : undefined}
                >
                  <span className="uqi-nav__code" aria-hidden="true">{item.code}</span>
                  {!collapsed && <span>{item.label}</span>}
                </Link>
              ))}
            </div>
          ))}
        </nav>

        <div className="uqi-sidebar__footer">
          {!collapsed && (
            <>
              <AqiLegend />
              <div className="uqi-sidebar__meta">
                <span>Provider</span>
                <strong>{toDisplayText(provider)}</strong>
                <span>Standard</span>
                <strong>{labelize(standard)}</strong>
                <span>Last updated</span>
                <strong>{generatedAt}</strong>
              </div>
            </>
          )}
          <button className="uqi-button uqi-button--ghost uqi-collapse-toggle" type="button" onClick={onToggleCollapse}>
            <span aria-hidden="true">{collapsed ? "»" : "«"}</span>
            {!collapsed && "Collapse"}
          </button>
        </div>
      </aside>
      {open && <button className="uqi-scrim" type="button" aria-label="Close navigation overlay" onClick={onClose} />}
    </>
  );
}

function DashboardHeader({
  activePage,
  city,
  loading,
  error,
  mapsLoadError,
  stationOptions,
  selectedStation,
  onStationChange,
  onMenu,
  onRefresh,
  onCityChange,
  onSettings,
  onLogout,
  user,
  generatedAt,
  decision,
  theme,
  onThemeChange,
}) {
  const title = commandTitle(activePage, city);
  const subtitle = commandSubtitle(activePage);
  const provider = decision?.forecast?.currentProvider || decision?.environmentalSignals?.provider || "CPCB";
  const standard = decision?.forecast?.forecastStandard || decision?.environmentalSignals?.aqiStandard || "INDIA_NAQI";
  const providerStatusLabel = provider === "Provider unavailable" ? "Provider pending" : `${labelize(provider)} live`;
  const standardStatusLabel = standard === "AQI standard unavailable" ? "Standard pending" : `${labelize(standard)} live`;
  const isOverview = activePage === "overview";

  if (isOverview) {
    return (
      <header className="uqi-topbar uqi-commandbar uqi-commandbar--overview">
        <div className="uqi-commandbar__secondary">
          <div className="uqi-contextbar">
            <button className="uqi-icon-button uqi-menu-button" type="button" onClick={onMenu} aria-label="Open navigation">=</button>
            <div className="uqi-selector-card uqi-selector-card--city uqi-context-city">
              <span className="uqi-selector-icon" aria-hidden="true">CY</span>
              <div>
                <span className="uqi-selector-label">City</span>
                <CitySelector city={city} loading={loading} onChange={onCityChange} />
              </div>
            </div>
            <label className="uqi-selector-card uqi-station-select uqi-context-station" title={selectedStation || "Station unavailable"}>
              <span className="uqi-selector-icon" aria-hidden="true">ST</span>
              <span className="uqi-selector-body">
                <span className="uqi-selector-label">Monitoring station</span>
                <select value={selectedStation} onChange={(event) => onStationChange(event.target.value)} disabled={stationOptions.length === 0}>
                  {stationOptions.length === 0 ? (
                    <option value="">Unavailable</option>
                  ) : (
                    stationOptions.map((station) => <option key={station} value={station}>{station}</option>)
                  )}
                </select>
              </span>
            </label>
            <ProfileSummary user={user} />
            <div className="uqi-context-actions" aria-label="Snapshot status and actions">
              <StatusBadge tone={error ? "critical" : loading ? "medium" : "low"} label={error ? "API error" : providerStatusLabel} />
              <StatusBadge tone="low" label={standardStatusLabel} />
              <StatusBadge tone={mapsLoadError ? "medium" : "low"} label={mapsLoadError ? "Map degraded" : "Snapshot ready"} />
              <StatusBadge tone="neutral" label={generatedAt} />
              <button className={`uqi-icon-button uqi-refresh-button ${loading ? "is-loading" : ""}`} type="button" onClick={onRefresh} disabled={loading} aria-label="Refresh dashboard">R</button>
              <button className="uqi-icon-button" type="button" onClick={onSettings} aria-label="Open settings">S</button>
              <ThemeToggle theme={theme} onChange={onThemeChange} />
              <button className="uqi-logout-button" type="button" onClick={onLogout}>Logout</button>
            </div>
          </div>
        </div>
      </header>
    );
  }

  return (
    <header className={`uqi-topbar uqi-commandbar uqi-commandbar--${activePage}`}>
      <div className="uqi-commandbar__primary">
        <div className="uqi-topbar__title">
          <button className="uqi-icon-button uqi-menu-button" type="button" onClick={onMenu} aria-label="Open navigation">=</button>
          <div>
            <BackToRoleSelection />
            <strong>{title}</strong>
            <span className="uqi-command-subtitle">{subtitle}</span>
          </div>
        </div>

        <RoleSwitcher active="municipal" />

        <div className="uqi-user-cluster">
          <button className="uqi-icon-button" type="button" aria-label="Notifications">!</button>
          <button className="uqi-icon-button" type="button" aria-label="Help">?</button>
          <div className="uqi-profile" title={user?.name || user?.email || "Administrator"}>
            {(user?.name || user?.email || "AD").slice(0, 2).toUpperCase()}
          </div>
          <div className="uqi-user-cluster__text">
            <strong>{user?.name || user?.email || "Administrator"}</strong>
            <span>Municipal Admin</span>
          </div>
        </div>
      </div>

      <div className="uqi-commandbar__secondary">
        <div className="uqi-topbar__controls uqi-contextbar">
        <div className="uqi-selector-card uqi-selector-card--city">
          <span className="uqi-selector-icon" aria-hidden="true">CY</span>
          <div>
            <span className="uqi-selector-label">City</span>
            <CitySelector city={city} loading={loading} onChange={onCityChange} />
          </div>
        </div>
        <label className="uqi-selector-card uqi-station-select" title={selectedStation || "Station unavailable"}>
          <span className="uqi-selector-icon" aria-hidden="true">ST</span>
          <span className="uqi-selector-body">
            <span className="uqi-selector-label">Monitoring station</span>
          <select value={selectedStation} onChange={(event) => onStationChange(event.target.value)} disabled={stationOptions.length === 0}>
            {stationOptions.length === 0 ? (
              <option value="">Unavailable</option>
            ) : (
              stationOptions.map((station) => <option key={station} value={station}>{station}</option>)
            )}
          </select>
          </span>
        </label>

          <div className="uqi-topbar__status" aria-label="Snapshot status">
            <StatusBadge tone={error ? "critical" : loading ? "medium" : "low"} label={error ? "API error" : providerStatusLabel} />
            <StatusBadge tone="low" label={standardStatusLabel} />
            <StatusBadge tone={mapsLoadError ? "medium" : "low"} label={mapsLoadError ? "Map degraded" : "Snapshot ready"} />
            <StatusBadge tone="neutral" label={generatedAt} />
            <button className={`uqi-icon-button uqi-refresh-button ${loading ? "is-loading" : ""}`} type="button" onClick={onRefresh} disabled={loading} aria-label="Refresh dashboard">R</button>
          <button className="uqi-icon-button" type="button" onClick={onSettings} aria-label="Open settings">S</button>
          </div>
        </div>
      </div>
    </header>
  );
}

function ProfileSummary({ user }) {
  const displayName = user?.name || "Admin Official";
  const initials = (displayName || user?.email || "AD").slice(0, 2).toUpperCase();
  return (
    <div className="uqi-context-profile" title={`${displayName} - Municipal Admin`}>
      <span className="uqi-profile">{initials}</span>
      <span>
        <strong>{displayName}</strong>
        <small>Municipal Admin</small>
      </span>
    </div>
  );
}

function ThemeToggle({ theme, onChange }) {
  const isDark = theme === "dark";
  return (
    <button
      className={`uqi-theme-toggle ${isDark ? "is-dark" : "is-light"}`}
      type="button"
      role="switch"
      aria-checked={isDark}
      aria-label={`Switch to ${isDark ? "light" : "dark"} mode`}
      onClick={() => onChange(isDark ? "light" : "dark")}
    >
      <span aria-hidden="true">Light</span>
      <span aria-hidden="true">Dark</span>
      <i aria-hidden="true" />
    </button>
  );
}

function AqiLegend() {
  return (
    <div className="uqi-aqi-legend" aria-label="AQI legend">
      <span><i className="tone-low" />0-50 Good</span>
      <span><i className="tone-medium" />51-100 Moderate</span>
      <span><i className="tone-high" />101-200 Poor</span>
      <span><i className="tone-high" />201-300 Very Poor</span>
      <span><i className="tone-critical" />301+ Severe</span>
    </div>
  );
}

function OverviewPage({ context }) {
  const { decision, timeline, activeFrame, mapsReady } = context;
  return (
    <motion.div className="uqi-page-stack" variants={staggerContainer} initial="initial" animate="animate">
      <section className="uqi-overview-command-grid">
        <CurrentAqiCard decision={decision} />
        <CitySummaryCard decision={decision} />
        <LiveForecastCard forecastResult={decision?.forecast} compact />
      </section>

      <section className="uqi-command-row uqi-command-row--insights">
        <CollectorModelStatusCard decision={decision} timeline={timeline} />
        <RiskDecisionCard decision={decision} activeFrame={activeFrame} />
        <SourceAttributionCard attribution={decision?.attribution} compact />
      </section>

      <section className="uqi-command-row uqi-command-row--operations">
        <EnforcementSummaryCard decision={decision} />
        <HealthAdvisoryPreview decision={decision} />
        <CommandCenterBottomCards context={context} />
      </section>

      <GeospatialOverviewCard context={context} mapsReady={mapsReady} />
    </motion.div>
  );
}

function CommandCenterBottomCards({ context }) {
  const decision = context.decision || {};
  const forecastPoints = getForecastPoints(decision.forecast);
  return (
    <MotionCard className="uqi-panel uqi-utility-summary-card">
      <PanelHeader eyebrow="Quick Actions" title="Operational status" chip={decision.engineStatus?.degradedMode ? "Degraded" : "Ready"} />
      <div className="uqi-quick-actions">
        <Link to="/gov/alerts">Broadcast alert</Link>
        <Link to="/gov/reports">Open report</Link>
        <Link to="/gov/enforcement">Review queue</Link>
        <Link to="/gov/maps">Open map</Link>
      </div>
      <div className="uqi-status-rows">
        <span><i className="tone-low" /><b>Data ingestion</b><strong>{decision.environmentalSignals?.aqiAvailable === false ? "Unavailable" : "Online"}</strong></span>
        <span><i className={context.error ? "tone-critical" : "tone-low"} /><b>API services</b><strong>{context.error ? "Attention" : "Healthy"}</strong></span>
        <span><i className="tone-neutral" /><b>Forecast horizons</b><strong>{forecastPoints.length || "Unavailable"}</strong></span>
        <span><i className="tone-low" /><b>Last sync</b><strong>{context.generatedAt}</strong></span>
      </div>
    </MotionCard>
  );
}

function HealthAdvisoryPreview({ decision }) {
  const advisory = decision?.advisories || {};
  const advisories = asArray(advisory.advisories || advisory.recommendations || advisory.actions);
  const first = advisories[0] || {};
  const audience = advisoryAudienceKey(first);
  return (
    <MotionCard className="uqi-panel uqi-health-preview">
      <PanelHeader eyebrow="Health Advisory" title={advisoryTitle(first, audience)} chip={labelize(advisory.riskLevel || first.severity || "Low risk")} />
      <p className="uqi-note">{advisoryGuidance(first)}</p>
      <div className="uqi-audience-row">
        {["General", "Children", "Elderly", "Respiratory"].map((label) => (
          <span key={label}>{label}</span>
        ))}
      </div>
      <Link className="uqi-inline-action" to="/gov/health-advisory">View guidelines →</Link>
    </MotionCard>
  );
}

function ForecastPage({ context }) {
  const points = getForecastPoints(context.decision?.forecast);
  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Forecast"
        title="Live forecast and historical replay"
        note="Live forecasts remain separate from replay state. Unavailable values are shown as unavailable, never as zero."
      />
      <LiveForecastCard forecastResult={context.decision?.forecast} />
      <HistoricalReplayCard expanded />
      <section className="uqi-panel">
        <PanelHeader eyebrow="Model Details" title="Horizon diagnostics" chip={`${points.length} horizons`} />
        <div className="uqi-table-wrap">
          <table className="uqi-table">
            <thead>
              <tr>
                <th>Horizon</th>
                <th>Prediction</th>
                <th>Engine</th>
                <th>Model</th>
                <th>Promotion</th>
                <th>Fallback reason</th>
                <th>Confidence</th>
              </tr>
            </thead>
            <tbody>
              {points.map((point) => (
                <tr key={point.key}>
                  <td>{point.key}</td>
                  <td>{formatAqi(point.predictedAqi)}</td>
                  <td>{engineLabel(point, context.decision?.forecast)}</td>
                  <td>{toDisplayText(point.modelVersion || point.modelFamily, "Unavailable")}</td>
                  <td>{labelize(point.modelPromotionStatus || point.promotionStatus, "Unavailable")}</td>
                  <td>{fallbackReasonLabel(point.fallbackReason || asArray(point.insufficiencyReasons)[0])}</td>
                  <td>{confidenceText(point.confidence)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function HistoricalReplayPage() {
  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Historical Replay"
        title="Archive-backed forecast validation"
        note="Replay controls and results are isolated from the live dashboard context. Missing archive frames remain explicitly unavailable."
      />
      <HistoricalReplayCard expanded />
    </div>
  );
}

function StationsPage({ context }) {
  const forecast = context.decision?.forecast || {};
  const points = getForecastPoints(forecast);
  const selectedName = context.selectedStation || stationNameFromDecision(context.decision);
  const rows = [
    {
      name: stationNameFromDecision(context.decision),
      key: forecast.stationKey || points.find((point) => point.stationKey)?.stationKey || "Unavailable",
      observations: points.reduce((max, point) => Math.max(max, asNumber(point.validObservationCount, 0)), 0),
      provider: forecast.currentProvider || context.decision?.environmentalSignals?.provider,
      status: "Selected",
      kind: "selected",
      updatedAt: context.decision?.environmentalSignals?.observedAt || context.decision?.generatedAt,
    },
    ...VERIFIED_REPLAY_STATIONS.map((name) => ({
      name,
      key: "Verified replay station",
      observations: "Archive dependent",
      provider: "Historical archive",
      status: "Replay eligible when supported",
      kind: "archive",
      updatedAt: "Replay catalogue",
    })),
  ];

  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Stations"
        title="Station-level operational view"
        note="The dashboard keeps the selected station's current AQI, pollutants, weather, and history together. No cross-station artifact mapping is performed in the frontend."
      />
      <section className="uqi-panel">
        <PanelHeader eyebrow="Catalogue" title="Operational stations in view" chip={`${rows.length} visible`} />
        <div className="uqi-station-grid">
          {rows.map((row) => (
            <article className={`uqi-mini-card uqi-station-card is-${row.kind} ${row.name === selectedName ? "is-selected" : ""}`} key={`${row.name}-${row.key}`}>
              <span className="uqi-card-kicker">{row.name === selectedName ? "Current live selection" : row.status}</span>
              <strong>{row.name}</strong>
              <dl className="uqi-definition-grid">
                <div><dt>Station key</dt><dd>{toDisplayText(row.key)}</dd></div>
                <div><dt>Observations</dt><dd>{toDisplayText(row.observations)}</dd></div>
                <div><dt>Provider</dt><dd>{toDisplayText(row.provider)}</dd></div>
                <div><dt>Last update</dt><dd>{row.kind === "selected" ? formatDateTime(row.updatedAt) : toDisplayText(row.updatedAt)}</dd></div>
              </dl>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}

function MapsPage({ context }) {
  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Maps"
        title="Geospatial intelligence"
        note="Layer controls use existing geospatial outputs. Leaflet/OpenStreetMap rendering keeps degraded evidence visible without synthesizing geometry."
      />
      <section className="uqi-panel uqi-panel--map-page">
        <GisDecisionMap city={context.city} snapshot={context.decision} mapsReady={context.mapsReady} />
      </section>
    </div>
  );
}

function SourceAnalysisPage({ context }) {
  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Source Analysis"
        title="Evidence-weighted attribution"
        note="Unknown and partial evidence remain visible. These are model-derived estimates, not laboratory source-apportionment measurements."
      />
      <SourceAttributionCard attribution={context.decision?.attribution} />
    </div>
  );
}

function EnforcementPage({ context }) {
  const enforcement = context.decision?.enforcement || {};
  const priorityActions = asArray(context.decision?.priorityActions);
  const actions = asArray(enforcement.actions || enforcement.recommendedActions || enforcement.recommendations);
  const agencies = asArray(enforcement.agencies || enforcement.responsibleAgencies);
  const tasks = actions.length > 0 ? actions : priorityActions;

  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Enforcement"
        title="Municipal action queue"
        note="This page only renders the existing enforcement response. It does not trigger enforcement workflow changes."
      />
      <section className="uqi-enforcement-grid">
        <MetricCard label="Priority" value={labelize(enforcement.priority || enforcement.severity, "Unavailable")} tone="medium" />
        <MetricCard label="Agencies" value={agencies.length || "Unavailable"} tone="low" />
        <MetricCard label="Actions" value={tasks.length || "Unavailable"} tone="neutral" />
        <MetricCard label="Confidence" value={confidenceText(enforcement.confidence)} tone="low" />
      </section>
      <section className="uqi-panel">
        <PanelHeader eyebrow="Recommended Actions" title="Actionable enforcement items" chip={`${tasks.length} items`} />
        <ActionList items={tasks} empty="No enforcement actions were returned for this snapshot." variant="enforcement" />
      </section>
      <section className="uqi-panel">
        <PanelHeader eyebrow="Agency Coordination" title="Responsible teams" chip={`${agencies.length} agencies`} />
        {agencies.length === 0 ? (
          <EmptyLine text="No agency assignments were returned." />
        ) : (
          <div className="uqi-tag-list">{agencies.map((agency) => <span key={toDisplayText(agency)}>{toDisplayText(agency)}</span>)}</div>
        )}
      </section>
    </div>
  );
}

function HealthAdvisoryPage({ context }) {
  const advisory = context.decision?.advisories || {};
  const advisories = asArray(advisory.advisories || advisory.recommendations || advisory.actions);
  const groups = asArray(advisory.vulnerableGroups || advisory.sensitiveGroups);
  const groupedAdvisories = advisories.reduce((acc, item) => {
    const group = advisoryAudienceKey(item);
    if (!acc[group]) acc[group] = [];
    acc[group].push(item);
    return acc;
  }, {});
  groups.forEach((group) => {
    const key = advisoryAudienceKey({ group });
    if (!groupedAdvisories[key]) groupedAdvisories[key] = [];
  });

  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Health Advisory"
        title="Citizen-facing health guidance"
        note="Advisories are separated from enforcement so public guidance stays readable and operational decisions stay distinct."
      />
      <section className="uqi-enforcement-grid">
        <MetricCard label="Risk level" value={labelize(advisory.riskLevel || advisory.healthRiskLevel, "Unavailable")} tone="medium" />
        <MetricCard label="Confidence" value={confidenceText(advisory.confidence)} tone="low" />
        <MetricCard label="Groups" value={groups.length || "Unavailable"} tone="neutral" />
        <MetricCard label="Updated" value={formatDateTime(advisory.generatedAt || context.decision?.generatedAt)} tone="neutral" />
      </section>
      <section className="uqi-panel">
        <PanelHeader eyebrow="Guidance" title="Health advisories" chip={`${advisories.length} items`} />
        <HealthAdvisoryGroups grouped={groupedAdvisories} empty="No health advisories were returned for this snapshot." />
      </section>
      <section className="uqi-panel">
        <PanelHeader eyebrow="Sensitive Groups" title="Groups needing extra caution" chip={`${groups.length} groups`} />
        {groups.length === 0 ? <EmptyLine text="No sensitive groups were returned." /> : (
          <div className="uqi-tag-list">{groups.map((group) => <span key={toDisplayText(group)}>{toDisplayText(group)}</span>)}</div>
        )}
      </section>
    </div>
  );
}

function AlertsPage({ context }) {
  const points = getForecastPoints(context.decision?.forecast);
  const alertable = points.filter((point) => asNumber(point.predictedAqi, -1) > 200 || point.fallbackReason);
  return (
    <UtilityPage
      eyebrow="Alerts"
      title="Alert readiness"
      note="Alert candidates are derived from the current forecast response and fallback diagnostics. No notification is sent from this page."
      items={alertable}
      empty="No high-risk or fallback-driven alert candidates are present in the current response."
      renderItem={(point) => (
        <>
          <strong>{point.key}: AQI {formatAqi(point.predictedAqi)}</strong>
          <span>{engineLabel(point, context.decision?.forecast)} - {fallbackReasonLabel(point.fallbackReason || asArray(point.insufficiencyReasons)[0])}</span>
          <details className="uqi-details">
            <summary>Diagnostic detail</summary>
            <dl className="uqi-definition-grid">
              <div><dt>Horizon</dt><dd>{point.key}</dd></div>
              <div><dt>Severity</dt><dd>{labelize(getAqiTone(point.predictedAqi), "Unavailable")}</dd></div>
              <div><dt>Engine</dt><dd>{engineLabel(point, context.decision?.forecast)}</dd></div>
              <div><dt>Reason</dt><dd>{fallbackReasonLabel(point.fallbackReason || asArray(point.insufficiencyReasons)[0])}</dd></div>
            </dl>
          </details>
        </>
      )}
    />
  );
}

function ReportsPage({ context }) {
  const forecastPoints = getForecastPoints(context.decision?.forecast);
  const advisories = asArray(context.decision?.advisories?.advisories || context.decision?.advisories?.recommendations);
  const reportItems = [
    { title: "Current AQI", value: formatAqi(context.decision?.currentAQI ?? context.decision?.environmentalSignals?.currentAqi) },
    { title: "Station", value: stationNameFromDecision(context.decision) },
    { title: "Provider", value: toDisplayText(context.decision?.forecast?.currentProvider || context.decision?.environmentalSignals?.provider) },
    { title: "Forecast state", value: forecastPoints.length ? `${forecastPoints.length} horizons` : "Unavailable" },
    { title: "Advisory state", value: advisories.length ? `${advisories.length} advisories` : "Unavailable" },
    { title: "Forecast snapshot", value: toDisplayText(context.decision?.forecast?.snapshotId, "Unavailable") },
    { title: "Source snapshot", value: toDisplayText(context.decision?.attribution?.snapshotId, "Unavailable") },
    { title: "Generated", value: context.generatedAt },
  ];
  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Reports"
        title="Decision report builder"
        note="This page summarizes available evidence for export workflows without creating new backend report records."
      />
      <section className="uqi-report-grid">
        {reportItems.map((item) => <MetricCard key={item.title} label={item.title} value={item.value} tone="neutral" />)}
      </section>
      <section className="uqi-panel">
        <PanelHeader eyebrow="Preview" title="Operational report preview" chip="Read-only" />
        <dl className="uqi-definition-grid">
          <div><dt>Decision snapshot</dt><dd>{toDisplayText(context.decision?.snapshotId)}</dd></div>
          <div><dt>Location hash</dt><dd>{toDisplayText(context.decision?.locationHash)}</dd></div>
          <div><dt>Source attribution</dt><dd>{context.decision?.attribution ? "Available" : "Unavailable"}</dd></div>
          <div><dt>Enforcement</dt><dd>{context.decision?.enforcement ? "Available" : "Unavailable"}</dd></div>
        </dl>
      </section>
    </div>
  );
}

function DataExplorerPage({ context }) {
  const rows = [
    ["Current AQI", formatAqi(context.decision?.currentAQI ?? context.decision?.environmentalSignals?.currentAqi)],
    ["Station", stationNameFromDecision(context.decision)],
    ["Provider", toDisplayText(context.decision?.forecast?.currentProvider || context.decision?.environmentalSignals?.provider)],
    ["Forecast standard", labelize(context.decision?.forecast?.forecastStandard || context.decision?.environmentalSignals?.aqiStandard)],
    ["Location hash", toDisplayText(context.decision?.locationHash)],
    ["Snapshot", toDisplayText(context.decision?.snapshotId)],
  ];
  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Data Explorer"
        title="Current response fields"
        note="Field values are read from the live decision response. Missing values are unavailable, not inferred."
      />
      <section className="uqi-panel">
        <PanelHeader eyebrow="Response" title="Key operational fields" chip={`${rows.length} fields`} />
        <div className="uqi-table-wrap">
          <table className="uqi-table">
            <tbody>
              {rows.map(([key, value]) => (
                <tr key={key}><th>{key}</th><td>{value}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function SettingsPage({ context }) {
  const sections = [
    {
      title: "Navigation",
      rows: [
        ["Compact navigation", "Use the sidebar collapse button on desktop to reduce navigation width.", false],
        ["AQI legend", "AQI categories remain visible in the sidebar footer on desktop.", true],
      ],
    },
    {
      title: "Display",
      rows: [
        ["Unavailable states", "Missing backend values remain explicitly unavailable in tables and cards.", true],
        ["Reduced visual motion", "System reduced-motion preference is respected by dashboard transitions.", true],
      ],
    },
    {
      title: "Data context",
      rows: [
        ["Station isolation", `Selected station: ${context.selectedStation || stationNameFromDecision(context.decision)}`, true],
      ],
    },
  ];
  return (
    <div className="uqi-page-stack">
      <PageIntro
        eyebrow="Settings"
        title="Workspace settings"
        note="Settings here control presentation only. They do not change backend providers, model promotion, advisories, or enforcement logic."
      />
      {sections.map((section) => (
        <section className="uqi-panel" key={section.title}>
          <PanelHeader eyebrow="Settings" title={section.title} chip="Presentation" />
          <div className="uqi-settings-grid">
            {section.rows.map(([title, text, checked]) => (
              <ToggleRow key={title} title={title} text={text} checked={checked} readOnly />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function CurrentAqiCard({ decision }) {
  const signals = decision?.environmentalSignals || {};
  const currentAqi = decision?.currentAQI ?? signals.currentAqi ?? signals.aqi;
  const hasCurrentAqi = Number.isFinite(Number(currentAqi));
  const tone = getAqiTone(currentAqi);
  const category = signals.aqiCategory || signals.category || decision?.forecast?.forecast?.["24h"]?.aqiCategory;
  const pollutantRows = [
    ["PM2.5", signals.pm25 ?? signals.pollutants?.pm25],
    ["PM10", signals.pm10 ?? signals.pollutants?.pm10],
    ["O3", signals.o3 ?? signals.pollutants?.o3],
    ["NO2", signals.no2 ?? signals.pollutants?.no2],
    ["SO2", signals.so2 ?? signals.pollutants?.so2],
  ].filter(([, value]) => value !== undefined && value !== null && value !== "");
  const gaugeValue = Math.max(0, Math.min(100, (Number(currentAqi) || 0) / 500 * 100));
  return (
    <MotionCard className={`uqi-panel uqi-panel--aqi uqi-aqi-hero tone-${tone}`}>
      <PanelHeader eyebrow="Current AQI" title={stationNameFromDecision(decision)} chip={labelize(category, "Category unavailable")} />
      <div className="uqi-aqi-hero__body">
        <div className={`uqi-aqi-gauge ${hasCurrentAqi ? "" : "is-unavailable"}`} style={{ "--gauge": gaugeValue }}>
          <svg viewBox="0 0 120 120" aria-hidden="true">
            <circle cx="60" cy="60" r="50" />
            <motion.circle
              cx="60"
              cy="60"
              r="50"
              initial={{ pathLength: 0 }}
              animate={{ pathLength: gaugeValue / 100 }}
              transition={{ duration: 0.9, ease: "easeOut" }}
            />
          </svg>
          {hasCurrentAqi ? <AnimatedNumber value={currentAqi} className="uqi-aqi-value" /> : <span className="uqi-aqi-value" title="Unavailable">N/A</span>}
        </div>
        <div className="uqi-aqi-meta">
          <strong>{labelize(category, "Category unavailable")}</strong>
          <span>{labelize(signals.dominantPollutant || signals.primaryPollutant, "Dominant pollutant unavailable")}</span>
          <small>Observed {formatDateTime(signals.observedAt || signals.timestamp || decision?.snapshotObservedAt)}</small>
        </div>
      </div>
      <div className="uqi-pollutant-bars">
        {(pollutantRows.length ? pollutantRows : [["PM2.5", null], ["PM10", null], ["O3", null]]).slice(0, 5).map(([label, value]) => {
          const amount = Math.max(8, Math.min(100, Number(value) || 12));
          return (
            <div key={label}>
              <span>{label}</span>
              <b>{value == null ? "NA" : value}</b>
              <i><motion.em initial={{ scaleX: 0 }} animate={{ scaleX: amount / 100 }} transition={{ duration: 0.7, ease: "easeOut" }} /></i>
            </div>
          );
        })}
      </div>
      <details className="uqi-details">
        <summary>View details</summary>
        <dl className="uqi-definition-grid">
          <div><dt>Provider</dt><dd>{toDisplayText(signals.provider || decision?.forecast?.currentProvider)}</dd></div>
          <div><dt>Standard</dt><dd>{labelize(signals.aqiStandard || decision?.forecast?.forecastStandard, "Unavailable")}</dd></div>
          <div><dt>Snapshot ID</dt><dd>{toDisplayText(decision?.snapshotId)}</dd></div>
          <div><dt>Location hash</dt><dd>{toDisplayText(decision?.locationHash)}</dd></div>
        </dl>
      </details>
    </MotionCard>
  );
}

function CitySummaryCard({ decision }) {
  const summary = decision?.environmentalSignals?.citySummary || decision?.citySummary || {};
  const hasSummary = Object.keys(summary || {}).length > 0;
  const items = [
    ["Avg AQI", summary.medianAqi ?? summary.medianAQI],
    ["Stations", summary.freshStationCount ?? summary.stationCount],
    ["Severe", summary.severeAlertCount ?? summary.severeAlerts ?? 0],
    ["Coverage", summary.coveragePercent ?? summary.availabilityPercent],
  ];
  const trend = items.map(([, value], index) => Math.max(12, Math.min(88, Number(value) || 22 + index * 12)));
  return (
    <MotionCard className="uqi-panel uqi-city-pulse-card">
      <PanelHeader eyebrow="City Summary" title="City pulse" chip={hasSummary ? "Available" : "Insufficient"} />
      <div className="uqi-city-metrics">
        {items.map(([label, value]) => (
          <div key={label}><span>{label}</span><strong>{toDisplayText(value)}</strong></div>
        ))}
      </div>
      <div className="uqi-mini-trend" aria-hidden="true">
        {trend.map((height, index) => (
          <motion.i key={index} initial={{ scaleY: 0 }} animate={{ scaleY: 1 }} transition={{ delay: index * 0.06, duration: 0.35 }} style={{ height: `${height}%` }} />
        ))}
      </div>
      <p className="uqi-note">
        {hasSummary
          ? toDisplayText(summary.explanation || summary.overview, "City summary is based on available fresh same-standard stations.")
          : "Insufficient fresh same-standard stations for city summary."}
      </p>
    </MotionCard>
  );
}

function RiskDecisionCard({ decision, activeFrame }) {
  const summary = decision?.summary || {};
  const risk = decision?.riskAssessment || {};
  return (
    <MotionCard className="uqi-panel uqi-risk-decision-card">
      <PanelHeader eyebrow="Risk and Decision" title={labelize(risk.riskLevel || risk.level, "Risk unavailable")} chip={activeFrame?.label || "Live"} />
      <p className="uqi-lead">{toDisplayText(summary.whatShouldOfficialsDoNow || risk.recommendation, "No immediate action summary returned.")}</p>
      <div className="uqi-tag-list">
        {asArray(risk.keyDrivers || risk.drivers || decision?.priorityActions).slice(0, 5).map((item, index) => (
          <span key={`${toDisplayText(item)}-${index}`}>{toDisplayText(item)}</span>
        ))}
      </div>
    </MotionCard>
  );
}

function EnforcementSummaryCard({ decision }) {
  const enforcement = decision?.enforcement || {};
  const priorityActions = asArray(decision?.priorityActions);
  const actions = asArray(enforcement.actions || enforcement.recommendedActions || enforcement.recommendations);
  const agencies = asArray(enforcement.agencies || enforcement.responsibleAgencies);
  const items = (actions.length > 0 ? actions : priorityActions).slice(0, 3);
  return (
    <MotionCard className="uqi-panel uqi-enforcement-summary-card">
      <PanelHeader eyebrow="Enforcement Summary" title={labelize(enforcement.priority || enforcement.severity, "Action queue")} chip={`${items.length} items`} />
      <div className="uqi-status-rows">
        <span><i className="tone-medium" /><b>Actions</b><strong>{items.length || "Unavailable"}</strong></span>
        <span><i className="tone-low" /><b>Agencies</b><strong>{agencies.length || "Unavailable"}</strong></span>
        <span><i className="tone-neutral" /><b>Confidence</b><strong>{confidenceText(enforcement.confidence)}</strong></span>
      </div>
      <ActionList items={items} empty="No enforcement actions were returned." compact />
      <Link className="uqi-inline-action" to="/gov/enforcement">Open enforcement</Link>
    </MotionCard>
  );
}

function CollectorModelStatusCard({ decision, timeline }) {
  const forecast = decision?.forecast || {};
  const points = getForecastPoints(forecast);
  const promoted = points.filter((point) => point.modelPromotionStatus === "PROMOTED" || point.promotionStatus === "PROMOTED").length;
  const fallback = points.filter(hasFallbackDiagnostics).length;
  const diagnostic = toDisplayText(decision?.engineStatus?.message, "No collector/model diagnostic message was returned.");
  return (
    <MotionCard className="uqi-panel uqi-system-health-card">
      <PanelHeader eyebrow="Collector and Model Status" title="Live engine health" chip={decision?.engineStatus?.degradedMode ? "Degraded" : "Stable"} />
      <div className="uqi-status-rows">
        <span><i className="tone-low" /><b>Provider status</b><strong>{decision?.environmentalSignals?.aqiAvailable === false ? "Unavailable" : "Online"}</strong></span>
        <span><i className={promoted > 0 ? "tone-low" : "tone-neutral"} /><b>Promoted ML horizons</b><strong>{promoted}</strong></span>
        <span><i className={fallback > 0 ? "tone-medium" : "tone-low"} /><b>Fallback horizons</b><strong>{fallback}</strong></span>
        <span><i className="tone-neutral" /><b>Timeline frames</b><strong>{asArray(timeline?.frames).length}</strong></span>
      </div>
      <p className="uqi-note uqi-line-clamp-3">{diagnostic}</p>
      <details className="uqi-details uqi-compact-details">
        <summary>View details</summary>
        <p className="uqi-note">{diagnostic}</p>
      </details>
    </MotionCard>
  );
}

function LiveForecastCard({ forecastResult, compact = false }) {
  const points = getForecastPoints(forecastResult);
  const fallbackPoints = points.filter(hasFallbackDiagnostics);
  return (
    <MotionCard className={`uqi-panel uqi-forecast-card-shell ${compact ? "is-compact" : ""}`}>
      <PanelHeader
        eyebrow="Live Forecast"
        title="24h / 48h / 72h outlook"
        chip={engineLabel({ engine: forecastResult?.engine || forecastResult?.mode, modelVersion: forecastResult?.modelVersion }, forecastResult)}
      />
      <p className="uqi-note">
        Station forecast only: {toDisplayText(forecastResult?.stationName || points.find((point) => point.stationName)?.stationName, "Unknown station")}.
        {!compact && <> Current provider {labelize(forecastResult?.currentProvider, "unavailable")}; forecast standard {labelize(forecastResult?.forecastStandard, "unavailable")}.</>}
      </p>
      {!compact && fallbackPoints.length > 0 && (
        <div className="uqi-warning-banner" role="status">
          <strong>Fallback or degraded forecast active.</strong>
          <span>{fallbackPoints.length} horizon{fallbackPoints.length === 1 ? "" : "s"} include fallback or degraded-history diagnostics.</span>
        </div>
      )}
      {compact && fallbackPoints.length > 0 && <span className="uqi-compact-diagnostic">{fallbackPoints.length} fallback horizon{fallbackPoints.length === 1 ? "" : "s"}</span>}
      {!compact && asArray(forecastResult?.warnings).length > 0 && (
        <div className="uqi-warning-row">
          {asArray(forecastResult?.warnings).map((warning) => <span key={warning}>{fallbackReasonLabel(warning)}</span>)}
        </div>
      )}
      <div className="uqi-forecast-layout">
        {!compact && <ForecastChart points={points} currentAqi={forecastResult?.currentAqi} />}
        <div className="uqi-horizon-list">
          {points.map((point) => (
            <ForecastHorizonCard key={point.key} point={point} forecastResult={forecastResult} compact={compact} />
          ))}
        </div>
      </div>
    </MotionCard>
  );
}

function ForecastChart({ points, currentAqi }) {
  const data = points.map((point) => ({
    name: point.key,
    aqi: point.predictedAqi == null || point.predictedAqi === "" ? null : Number(point.predictedAqi),
    lower: point.lowerBound == null ? null : Number(point.lowerBound),
    upper: point.upperBound == null ? null : Number(point.upperBound),
  }));
  const hasData = data.some((item) => Number.isFinite(item.aqi));
  if (!hasData) return <div className="uqi-chart-empty">Forecast chart unavailable because all horizon predictions are unavailable.</div>;
  return (
    <div className="uqi-chart-frame">
      <ResponsiveContainer width="100%" height={260}>
        <LineChart data={data} margin={{ top: 16, right: 16, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#d8e2ed" />
          <XAxis dataKey="name" />
          <YAxis domain={[0, "dataMax + 40"]} width={44} />
          <Tooltip formatter={(value) => formatAqi(value)} />
          {Number.isFinite(Number(currentAqi)) && (
            <ReferenceLine
              y={Number(currentAqi)}
              stroke="#1d4ed8"
              strokeDasharray="4 4"
              label={{ value: "Current AQI", position: "insideTopRight", fill: "#1d4ed8", fontSize: 11 }}
            />
          )}
          <Line type="monotone" dataKey="aqi" stroke="#0f766e" strokeWidth={3} dot={{ r: 5 }} connectNulls={false} />
          <Line type="monotone" dataKey="lower" stroke="#94a3b8" strokeDasharray="4 4" dot={false} connectNulls={false} />
          <Line type="monotone" dataKey="upper" stroke="#94a3b8" strokeDasharray="4 4" dot={false} connectNulls={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

function ForecastHorizonCard({ point, forecastResult, compact }) {
  const unavailable = point.predictedAqi === null || point.predictedAqi === undefined || point.predictedAqi === "";
  return (
    <article className={`uqi-horizon-card tone-${getAqiTone(point.predictedAqi)} ${unavailable ? "is-unavailable" : ""}`}>
      <div className="uqi-horizon-card__top">
        <strong>{point.key}</strong>
        <StatusBadge tone={unavailable ? "neutral" : getAqiTone(point.predictedAqi)} label={engineLabel(point, forecastResult)} />
      </div>
      <div className="uqi-horizon-card__value" title={unavailable ? "Unavailable" : undefined}>{compact && unavailable ? "N/A" : formatAqi(point.predictedAqi)}</div>
      {compact && <span className="uqi-horizon-compact-meta">{confidenceText(point.confidence)}</span>}
      {!compact && (
        <dl className="uqi-definition-grid">
          <div><dt>Range</dt><dd>{point.lowerBound == null || point.upperBound == null ? "Unavailable" : `${formatAqi(point.lowerBound)}-${formatAqi(point.upperBound)}`}</dd></div>
          <div><dt>Confidence</dt><dd>{confidenceText(point.confidence)}</dd></div>
          <div><dt>Model</dt><dd>{toDisplayText(point.modelVersion || point.modelFamily, "Unavailable")}</dd></div>
          <div><dt>Promotion</dt><dd>{labelize(point.modelPromotionStatus || point.promotionStatus, "Unavailable")}</dd></div>
        </dl>
      )}
      {!compact && (
        <details className="uqi-details">
          <summary>Model and fallback details</summary>
          <dl className="uqi-definition-grid">
            <div><dt>Fallback reason</dt><dd>{fallbackReasonLabel(point.fallbackReason || asArray(point.insufficiencyReasons)[0])}</dd></div>
            <div><dt>History</dt><dd>{toDisplayText(point.validObservationCount, "0")} observations / {toDisplayText(point.coverageHours, "0")} hours</dd></div>
            <div><dt>Target time</dt><dd>{formatDateTime(point.targetTime)}</dd></div>
            <div><dt>Standard</dt><dd>{labelize(point.aqiStandard || forecastResult?.forecastStandard, "Unavailable")}</dd></div>
            <div><dt>Provider</dt><dd>{labelize(point.provider || forecastResult?.provider || forecastResult?.currentProvider, "Unavailable")}</dd></div>
            <div><dt>Scope</dt><dd>{labelize(point.forecastScope, "Unavailable")}</dd></div>
            <div><dt>Baseline</dt><dd>{formatAqi(point.baselinePredictedAqi)}</dd></div>
          </dl>
          <p className="uqi-note">{toDisplayText(point.explanation || point.meteorologicalInfluence, "No forecast explanation returned.")}</p>
        </details>
      )}
    </article>
  );
}

function HistoricalReplayCard({ expanded = false }) {
  const [stations, setStations] = useState([]);
  const [stationKey, setStationKey] = useState("");
  const [date, setDate] = useState("");
  const [time, setTime] = useState("");
  const [validation, setValidation] = useState("Select a verified station, date, and time to validate replay availability.");
  const [replay, setReplay] = useState(null);
  const [replayFrameIndex, setReplayFrameIndex] = useState(1);
  const [replayLoading, setReplayLoading] = useState(false);
  const [replayError, setReplayError] = useState("");
  const replayRequestRef = useRef(null);
  const stationRequestRef = useRef(null);
  const replayTimelineFrames = asArray(replay?.timelineFrames);
  const selectedFrame = replayTimelineFrames[replayFrameIndex] || replayTimelineFrames[0] || null;
  const hasReplayFrames = replayTimelineFrames.length > 0;
  const fallbackStations = [
    { stationKey: "lucknow_gomti_nagar", stationName: "Gomti Nagar, Lucknow" },
    { stationKey: "delhi_ito", stationName: "ITO, Delhi" },
    { stationKey: "mumbai_bandra_kurla_complex", stationName: "Bandra Kurla Complex, Mumbai" },
  ];
  const stationOptions = stations.length > 0 ? stations : fallbackStations;
  const selectedStation = useMemo(
    () => stationOptions.find((item) => item.stationKey === stationKey) || stationOptions[0] || null,
    [stationOptions, stationKey],
  );
  const minReplayDate = dateInputValue(selectedStation?.earliestReplayTimestamp || selectedStation?.earliestValidReplayTimestamp);
  const maxReplayDate = dateInputValue(selectedStation?.latestReplayTimestamp || selectedStation?.latestValidReplayTimestamp);
  const replayRows = (Array.isArray(replay?.results) && replay.results.length > 0
    ? replay.results
    : Array.isArray(replay?.horizons) && replay.horizons.length > 0 ? replay.horizons : [24, 48, 72].map((hours) => ({
    horizonHours: hours,
    predictedAqi: null,
    actualAqi: null,
    absoluteError: null,
    percentageError: null,
    engine: null,
    modelVersion: null,
    promotionStatus: null,
    featureCoverage: null,
    fallbackReason: null,
    actualObservationTimestamp: null,
  })));

  useEffect(() => {
    const controller = new AbortController();
    stationRequestRef.current = controller;
    getHistoricalReplayStations({ signal: controller.signal })
      .then((result) => {
        const rows = Array.isArray(result) ? result : [];
        setStations(rows);
        const firstKey = rows[0]?.stationKey || fallbackStations[0].stationKey;
        setStationKey((current) => current || firstKey);
        const first = rows.find((item) => item.stationKey === firstKey) || rows[0];
        setDate((current) => current || dateInputValue(first?.earliestReplayTimestamp || first?.earliestValidReplayTimestamp));
        setTime((current) => current || timeInputValue(first?.earliestReplayTimestamp || first?.earliestValidReplayTimestamp));
      })
      .catch((err) => {
        if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
        setStations([]);
        setStationKey((current) => current || fallbackStations[0].stationKey);
        setValidation("Station replay catalogue is unavailable; verified archive station presets remain selectable.");
      });
    return () => controller.abort();
  }, []);

  async function runReplayValidation() {
    if (!stationKey || !date || !time) {
      setValidation("Validation state: station, date, and time are required before a replay request can run.");
      return;
    }
    if (replayRequestRef.current) {
      replayRequestRef.current.abort();
    }
    const controller = new AbortController();
    replayRequestRef.current = controller;
    setReplayLoading(true);
    setReplayError("");
    setReplay(null);
    setReplayFrameIndex(1);
    setValidation("Loading state: running historical replay without changing the live forecast.");
    try {
      const forecastIssueTime = new Date(`${date}T${time}:00Z`).toISOString();
      if (Number.isNaN(new Date(forecastIssueTime).getTime())) {
        setValidation("Validation state: malformed timestamp.");
        setReplayLoading(false);
        return;
      }
      const result = await runHistoricalReplay({ stationKey, forecastIssueTime }, { signal: controller.signal });
      const nextReplay = result && typeof result === "object" ? result : null;
      const nextFrames = asArray(nextReplay?.timelineFrames);
      setReplay(nextReplay);
      setReplayFrameIndex(nextFrames.length > 1 ? 1 : 0);
      setValidation(result?.status === "AVAILABLE"
        ? "Replay complete: predictions used only observations available at issue time; future actuals are comparison-only."
        : toDisplayText(result?.message, "No-valid-date state: replay is unavailable for this selection."));
    } catch (err) {
      if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
      setReplayError(err?.response?.data?.message || err?.message || "Historical replay request failed.");
      setValidation("API error state: historical replay did not complete.");
    } finally {
      if (replayRequestRef.current === controller) {
        replayRequestRef.current = null;
      }
      setReplayLoading(false);
    }
  }

  return (
    <MotionCard className="uqi-panel uqi-source-card-shell">
      <PanelHeader eyebrow="Historical Forecast Replay" title="Verified replay workspace" chip={hasReplayFrames ? `${replayTimelineFrames.length} frames` : "Awaiting replay"} />
      <p className="uqi-note">
        Replay is isolated from live forecasts. Only supported historical frames are shown; the UI does not synthesize historical predictions.
      </p>
      <div className="uqi-replay-controls">
        <label>
          <span>Station</span>
          <select
            value={stationKey}
            onChange={(event) => {
              const nextKey = event.target.value;
              const nextStation = stationOptions.find((item) => item.stationKey === nextKey);
              setStationKey(nextKey);
              setReplay(null);
              setReplayFrameIndex(1);
              setReplayError("");
              setDate(dateInputValue(nextStation?.earliestReplayTimestamp || nextStation?.earliestValidReplayTimestamp));
              setTime(timeInputValue(nextStation?.earliestReplayTimestamp || nextStation?.earliestValidReplayTimestamp));
              setValidation(nextStation?.status === "AVAILABLE"
                ? "Select a valid replay timestamp inside this station archive window."
                : "No-valid-date state: this station has no verified replay archive rows in the current database.");
            }}
          >
            {stationOptions.map((item) => (
              <option key={item.stationKey || item.stationName} value={item.stationKey}>
                {item.stationName || item.stationKey}{item.status && item.status !== "AVAILABLE" ? ` - ${labelize(item.status)}` : ""}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>Date</span>
          <input type="date" value={date} min={minReplayDate} max={maxReplayDate} onChange={(event) => setDate(event.target.value)} />
        </label>
        <label><span>Time</span><input type="time" value={time} onChange={(event) => setTime(event.target.value)} /></label>
        <button className="uqi-button" type="button" onClick={runReplayValidation} disabled={replayLoading}>
          {replayLoading ? "Running Replay" : "Run Historical Forecast"}
        </button>
      </div>
      <div className="uqi-replay-result">
        <strong>Historical Replay - This is not a current live forecast.</strong>
        <span>{validation}</span>
      </div>
      {selectedStation && (
        <div className="uqi-info-banner">
          Archive window: {formatDateTime(selectedStation.earliestReplayTimestamp || selectedStation.earliestValidReplayTimestamp)}
          {" to "}
          {formatDateTime(selectedStation.latestReplayTimestamp || selectedStation.latestValidReplayTimestamp)}.
          Provider {toDisplayText(selectedStation.provider)}, standard {labelize(selectedStation.aqiStandard, "Unavailable")}, timezone {toDisplayText(selectedStation.timezone)}.
        </div>
      )}
      {replayError && <div className="uqi-inline-error">{replayError}</div>}
      {replay && (
        <dl className="uqi-definition-grid uqi-replay-summary">
          <div><dt>Issue-time AQI</dt><dd>{formatAqi(replay.issueTimeAqi)}</dd></div>
          <div><dt>Issue observation</dt><dd>{formatDateTime(replay.issueObservationTime)}</dd></div>
          <div><dt>Engine</dt><dd>{labelize(replay.engine, "Unavailable")}</dd></div>
          <div><dt>Model version</dt><dd>{toDisplayText(replay.modelVersion)}</dd></div>
          <div><dt>Promotion</dt><dd>{labelize(replay.promotionStatus, "Unavailable")}</dd></div>
          <div><dt>Feature coverage</dt><dd>{replay.featureCoverage == null ? "Unavailable" : `${Math.round(replay.featureCoverage)}%`}</dd></div>
        </dl>
      )}
      <TimelineSlider
        frames={replayTimelineFrames}
        selectedIndex={replayFrameIndex}
        onChange={setReplayFrameIndex}
        loading={replayLoading}
        error={replayError}
      />
      {!hasReplayFrames && <EmptyLine text="Run a historical replay to load archive-backed timeline frames for this station and issue time." />}
      {hasReplayFrames && (
        <div className="uqi-replay-result">
          <strong>{selectedFrame?.label || "Selected frame"}</strong>
          <span>{toDisplayText(selectedFrame?.summary || selectedFrame?.description, "Historical frame unavailable")}</span>
        </div>
      )}
      <div className="uqi-table-wrap">
        <table className="uqi-table">
          <thead>
            <tr>
              <th>Horizon</th>
              <th>Predicted AQI</th>
              <th>Actual AQI</th>
              <th>Absolute Error</th>
              <th>Error Percentage</th>
              <th>Engine</th>
              <th>Model Version</th>
              <th>Promotion</th>
              <th>Feature Coverage</th>
              <th>Fallback Reason</th>
              <th>Actual Timestamp</th>
            </tr>
          </thead>
          <tbody>
            {replayRows.map((row) => (
              <tr key={row.horizonHours || row.horizon}>
                <td>{row.horizonHours ? `${row.horizonHours}h` : row.horizon}</td>
                <td>{formatAqi(row.predictedAqi)}</td>
                <td>{formatAqi(row.actualAqi)}</td>
                <td>{toDisplayText(row.absoluteError)}</td>
                <td>{row.percentageError == null ? "Unavailable" : `${row.percentageError.toFixed(1)}%`}</td>
                <td>{labelize(row.engine, "Unavailable")}</td>
                <td>{toDisplayText(row.modelVersion)}</td>
                <td>{labelize(row.promotionStatus, "Unavailable")}</td>
                <td>{row.featureCoverage == null ? "Unavailable" : `${Math.round(row.featureCoverage)}%`}</td>
                <td>{labelize(row.fallbackReason, "Unavailable")}</td>
                <td>{formatDateTime(row.actualObservationTimestamp || row.actualObservedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {expanded && (
        <details className="uqi-details" open>
          <summary>Replay support rules</summary>
          <p className="uqi-note">Supported stations are Gomti Nagar, ITO, and BKC when the existing timeline data includes matching verified frames. Unsupported dates remain unavailable.</p>
        </details>
      )}
    </MotionCard>
  );
}

function SourceAttributionCard({ attribution, compact = false }) {
  const sources = asArray(attribution?.sources);
  const displayedSources = compact ? sources.slice(0, 2) : sources;
  const hiddenCount = Math.max(0, sources.length - displayedSources.length);
  const dominantSource = attribution?.dominantSource;
  return (
    <section className={`uqi-panel uqi-source-attribution-card ${compact ? "is-compact" : ""}`}>
      <PanelHeader eyebrow="Source Attribution" title={labelize(dominantSource, "Dominant source unavailable")} chip={confidenceText(attribution?.overallConfidence)} />
      <p className="uqi-note">{toDisplayText(attribution?.explanation, "Attribution explanation was not included in the decision response.")}</p>
      <p className="uqi-disclaimer">Source contributions are model-based estimates from available evidence. Unknown and missing evidence are preserved instead of hidden.</p>
      <div className="uqi-source-bars">
        {sources.length === 0 ? (
          <EmptyLine text="Insufficient evidence. Source attribution is unavailable for this snapshot." />
        ) : (
          displayedSources.map((source) => <SourceRow key={source.sourceType || source.sourceId || source.displayName} source={source} compact={compact} />)
        )}
      </div>
      {compact && hiddenCount > 0 && <p className="uqi-note uqi-source-compact-note">+{hiddenCount} lower-confidence sources available in Source Analysis.</p>}
    </section>
  );
}

function SourceRow({ source, compact }) {
  const percent = Number(source?.estimatedContributionPercent ?? source?.percentage ?? source?.contributionPercent);
  const safePercent = Number.isFinite(percent) ? Math.max(0, Math.min(100, percent)) : 0;
  const evidence = asArray(source.supportingEvidence || source.evidence);
  return (
    <article className="uqi-source-row">
      <div className="uqi-source-row__top">
        <div>
          <strong>{toDisplayText(source.displayName, labelize(source.sourceType))}</strong>
          <span>{confidenceText(source.confidence)} confidence</span>
        </div>
        <b>{Number.isFinite(percent) ? `${Math.round(percent)}%` : "Unknown"}</b>
      </div>
      <div className="uqi-source-bar"><span style={{ width: `${safePercent}%` }} /></div>
      <div className="uqi-tag-list">
        <span>{labelize(source.dataOrigin || "derived_estimate")}</span>
        <span>{labelize(source.dataAvailability || "partial")}</span>
        <span>{evidence.length} evidence</span>
      </div>
      {!compact && (
        <details className="uqi-details">
          <summary>Evidence and limitations</summary>
          <ul className="uqi-list">
            {evidence.length === 0 ? <li>No supporting evidence returned.</li> : evidence.slice(0, 6).map((item, index) => <li key={index}>{toDisplayText(item)}</li>)}
          </ul>
          <p className="uqi-note">{toDisplayText(source.limitations || source.missingEvidence, "No limitations returned.")}</p>
        </details>
      )}
    </article>
  );
}

function GeospatialOverviewCard({ context }) {
  return (
    <MotionCard className="uqi-panel uqi-panel--map uqi-map-command-card">
      <PanelHeader eyebrow="Geospatial View" title="Map layers" chip={context.mapsLoadError ? "Degraded" : "Ready"} />
      <GisDecisionMap city={context.city} snapshot={context.decision} mapsReady={context.mapsReady} />
    </MotionCard>
  );
}

function CompactActionPanel({ decision }) {
  const priorityActions = asArray(decision?.priorityActions).slice(0, 4);
  const advisories = asArray(decision?.advisories?.advisories || decision?.advisories?.recommendations).slice(0, 3);
  return (
    <MotionCard className="uqi-panel uqi-decision-summary-card">
      <PanelHeader eyebrow="Decision Queue" title="Immediate next steps" chip={`${priorityActions.length + advisories.length} items`} />
      <ActionList items={[...priorityActions, ...advisories]} empty="No action or advisory items were returned." compact />
      <div className="uqi-panel-actions">
        <Link className="uqi-button" to="/gov/enforcement">Open enforcement</Link>
        <Link className="uqi-button uqi-button--secondary" to="/gov/health-advisory">Open advisory</Link>
      </div>
    </MotionCard>
  );
}

function ActionList({ items, empty, compact = false }) {
  const rows = asArray(items);
  if (rows.length === 0) return <EmptyLine text={empty} />;
  return (
    <div className={`uqi-action-list ${compact ? "is-compact" : ""}`}>
      {rows.map((item, index) => (
        <article className="uqi-action-item" key={`${toDisplayText(item)}-${index}`}>
          <strong>{toDisplayText(item.title || item.action || item.name || item, `Item ${index + 1}`)}</strong>
          <span>{actionDetailText(item)}</span>
        </article>
      ))}
    </div>
  );
}

function HealthAdvisoryGroups({ grouped, empty }) {
  const entries = Object.entries(grouped || {});
  if (entries.length === 0) return <EmptyLine text={empty} />;
  return (
    <div className="uqi-advisory-groups">
      {entries.map(([audience, items]) => (
        <section className="uqi-advisory-group" key={audience}>
          <div className="uqi-advisory-group__header">
            <strong>{audience}</strong>
            <StatusBadge tone={items.length > 0 ? "low" : "neutral"} label={items.length > 0 ? `${items.length} advisory` : "Unavailable"} />
          </div>
          {items.length === 0 ? (
            <EmptyLine text="No audience-specific advisory was returned." />
          ) : (
            <div className="uqi-action-list">
              {items.map((item, index) => (
                <HealthAdvisoryCard key={`${audience}-${index}`} item={item} audience={audience} />
              ))}
            </div>
          )}
        </section>
      ))}
    </div>
  );
}

function HealthAdvisoryCard({ item, audience }) {
  const evidence = parseEvidence(item?.evidence || item?.technicalEvidence || item?.supportingEvidence);
  return (
    <article className="uqi-action-item uqi-advisory-card">
      <div className="uqi-action-item__top">
        <strong>{advisoryTitle(item, audience)}</strong>
        <StatusBadge tone={getAqiTone(item?.aqi || item?.riskScore)} label={labelize(item?.severity || item?.riskLevel || item?.category, "Risk advisory")} />
      </div>
      <span>{advisoryGuidance(item)}</span>
      <div className="uqi-tag-list">
        <span>{audience}</span>
        {item?.confidence != null && <span>{confidenceText(item.confidence)} confidence</span>}
        {item?.horizon && <span>{labelize(item.horizon)}</span>}
      </div>
      <details className="uqi-details">
        <summary>Technical evidence</summary>
        {evidence.length === 0 ? (
          <EmptyLine text="No technical evidence rows were returned." />
        ) : (
          <div className="uqi-table-wrap">
            <table className="uqi-table uqi-table--compact">
              <thead>
                <tr>
                  <th>Dataset</th>
                  <th>Signal</th>
                  <th>Description</th>
                  <th>Confidence</th>
                </tr>
              </thead>
              <tbody>
                {evidence.map((row, index) => (
                  <tr key={index}>
                    <td>{toDisplayText(row.dataset)}</td>
                    <td>{toDisplayText(row.signal)}</td>
                    <td>{toDisplayText(row.description)}</td>
                    <td>{confidenceText(row.confidence)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </details>
    </article>
  );
}

function UtilityPage({ eyebrow, title, note, items, empty, renderItem }) {
  return (
    <div className="uqi-page-stack">
      <PageIntro eyebrow={eyebrow} title={title} note={note} />
      <section className="uqi-panel">
        <PanelHeader eyebrow={eyebrow} title={title} chip={`${items.length} items`} />
        {items.length === 0 ? <EmptyLine text={empty} /> : (
          <div className="uqi-action-list">
            {items.map((item, index) => <article className="uqi-action-item" key={index}>{renderItem(item)}</article>)}
          </div>
        )}
      </section>
    </div>
  );
}

function ToggleRow({ title, text, checked, readOnly }) {
  return (
    <label className="uqi-toggle-row">
      <span><strong>{title}</strong><small>{text}</small></span>
      <input type="checkbox" checked={checked} readOnly={readOnly} aria-label={title} />
      <i className="uqi-switch" aria-hidden="true" />
    </label>
  );
}

function PageIntro({ eyebrow, title, note }) {
  return (
    <section className="uqi-page-intro">
      <span className="uqi-eyebrow">{eyebrow}</span>
      <h1>{title}</h1>
      <p>{note}</p>
    </section>
  );
}

function PanelHeader({ eyebrow, title, chip }) {
  return (
    <div className="uqi-panel__header">
      <div>
        <span className="uqi-eyebrow">{eyebrow}</span>
        <h2 title={toDisplayText(title)}>{title}</h2>
      </div>
      {chip && <span className="uqi-chip" title={toDisplayText(chip)}>{chip}</span>}
    </div>
  );
}

function MetricCard({ label, value, tone = "neutral" }) {
  return (
    <article className={`uqi-metric-card tone-${tone}`}>
      <span>{label}</span>
      <strong>{toDisplayText(value)}</strong>
    </article>
  );
}

function StatusBadge({ tone = "neutral", label }) {
  return <span className={`uqi-status-badge tone-${tone}`} title={toDisplayText(label)}>{label}</span>;
}

function EmptyLine({ text }) {
  return <div className="uqi-empty-line">{text}</div>;
}

function PlaceRequiredView({ onSelectCity }) {
  return (
    <section className="uqi-panel uqi-place-required">
      <PanelHeader eyebrow="Place Required" title="Search and select a city or place" chip="Waiting" />
      <p className="uqi-note">Intelligence, maps, forecasts, timelines, and charts load only after a place search result provides coordinates.</p>
      <div className="uqi-preset-row" aria-label="Verified city presets">
        {CITY_PRESETS.map((city) => (
          <button className="uqi-button uqi-button--secondary" type="button" key={city.cityId} onClick={() => onSelectCity(city)}>
            {city.cityName}
          </button>
        ))}
      </div>
    </section>
  );
}

function CoreLoadingView() {
  return (
    <div className="uqi-page-stack">
      <RiskOverviewSkeleton />
      <div className="uqi-two-column">
        <PanelSkeleton eyebrow="Forecast" title="Loading forecast availability" lines={3} />
        <PanelSkeleton eyebrow="GIS Intelligence" title="Map layers loading independently" lines={4} />
      </div>
      <div className="uqi-two-column">
        <PanelSkeleton eyebrow="Attribution" title="Source signals loading" lines={4} />
        <PanelSkeleton eyebrow="Timeline" title="Timeline frames loading" lines={3} />
      </div>
    </div>
  );
}

function DecisionCopilotDrawer({ cityId = "", city, timelineFrame, degradedMode = false, suggestedQuestions, snapshotId, compactLauncher = false }) {
  const [open, setOpen] = useState(false);
  const [question, setQuestion] = useState("");
  const [response, setResponse] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const conversationIdRef = useRef(`copilot-${Date.now()}`);
  const requestRef = useRef(null);
  const drawerRef = useRef(null);
  const inputRef = useRef(null);
  const lastActiveRef = useRef(null);
  const questionOptions = useMemo(() => {
    const backendQuestions = asArray(response?.suggestedQuestions || suggestedQuestions)
      .map((item) => toDisplayText(item?.question || item, ""))
      .filter(Boolean);
    return (backendQuestions.length > 0 ? backendQuestions : [
      "Why is AQI worsening?",
      "What is the dominant source?",
      "Which agency should act first?",
      "What should schools do today?",
      "How reliable is this forecast?",
    ]).slice(0, 5);
  }, [response, suggestedQuestions]);
  const responseStatus = copilotStatus(response);
  const latestQuestion = response?.question || question;
  const retryLatest = () => submitQuestion(latestQuestion);

  useEffect(() => {
    if (requestRef.current) requestRef.current.abort();
    setResponse(null);
    setError("");
    setQuestion("");
  }, [cityId, city, timelineFrame, snapshotId]);

  useEffect(() => {
    if (!open) return undefined;
    lastActiveRef.current = document.activeElement;
    window.setTimeout(() => inputRef.current?.focus(), 0);
    const onKeyDown = (event) => {
      if (event.key === "Escape") {
        setOpen(false);
        return;
      }
      if (event.key !== "Tab" || !drawerRef.current) return;
      const focusable = drawerRef.current.querySelectorAll("button, textarea, input, select, a[href]");
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      lastActiveRef.current?.focus?.();
    };
  }, [open]);

  async function submitQuestion(nextQuestion) {
    const trimmed = String(nextQuestion || "").replace(/\s+/g, " ").trim().slice(0, 500);
    if (!trimmed) return;
    if (requestRef.current) requestRef.current.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    setLoading(true);
    setError("");
    try {
      const result = await queryDecisionCopilot({
        cityId,
        city,
        question: trimmed,
        timelineFrame,
        conversationId: conversationIdRef.current,
        snapshotId,
        signal: controller.signal,
      });
      setResponse(result && typeof result === "object" ? result : null);
    } catch (err) {
      if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
      setResponse(null);
      setError(err?.response?.data?.message || err?.message || "Copilot request failed.");
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null;
        setLoading(false);
      }
    }
  }

  const copilotNode = (
    <div className={`uqi-copilot-portal ${open ? "is-open" : ""}`}>
      <button className={`uqi-copilot-launcher ${open ? "is-open" : ""} ${compactLauncher ? "is-compact-launcher" : ""}`} type="button" onClick={() => setOpen(true)} aria-label="Open Decision Copilot">
        <strong>Decision Copilot</strong>
        <span>{response ? copilotModeLabel(response) : degradedMode ? "Partial-aware answers" : "Grounded answers"}</span>
      </button>
      {open && (
        <>
          <button className="uqi-copilot-scrim" type="button" aria-label="Close copilot" onClick={() => setOpen(false)} />
          <aside className="uqi-copilot-drawer" ref={drawerRef} role="dialog" aria-modal="true" aria-label="Decision Copilot">
            <div className="uqi-copilot-header">
              <PanelHeader eyebrow="Decision Copilot" title="Evidence-backed answers" chip={degradedMode ? "Degraded" : "Grounded"} />
              <p className="uqi-note">Uses only decision, forecast, attribution, geospatial, enforcement, advisory, and timeline outputs.</p>
            </div>
            <div className="uqi-copilot-scroll">
              <div className="uqi-copilot-prompts">
                {questionOptions.map((item) => (
                  <button key={item} type="button" disabled={loading} onClick={() => { setQuestion(item); submitQuestion(item); }}>
                    {item}
                  </button>
                ))}
              </div>
              {error && (
                <div className="uqi-inline-error" data-copilot-state="error">
                  <span>{error}</span>
                  <button className="uqi-button uqi-button--ghost" type="button" disabled={loading || !latestQuestion.trim()} onClick={retryLatest}>Retry</button>
                </div>
              )}
              {!loading && !error && !response && <EmptyLine text={`Ask a grounded question for ${city?.displayName || cityId || "the selected place"}.`} />}
              {loading && <p className="uqi-note" data-copilot-state="loading">Selecting grounded evidence and preparing a concise answer.</p>}
              {!loading && !error && response && (
                <div className={`uqi-copilot-response is-${responseStatus.toLowerCase()}`} data-copilot-state={responseStatus.toLowerCase()}>
                  <div className="uqi-copilot-response__badges">
                    <span className={`uqi-status-badge ${copilotModeTone(response)}`}>{copilotModeLabel(response)}</span>
                    {responseStatus === "PARTIAL" && <span className="uqi-status-badge tone-medium">Partial</span>}
                    {responseStatus === "UNAVAILABLE" && <span className="uqi-status-badge tone-critical">Unavailable</span>}
                  </div>
                  <div className="uqi-definition-grid">
                    <div><dt>Status</dt><dd>{labelize(responseStatus)}</dd></div>
                    <div><dt>Confidence</dt><dd>{confidenceText(response.confidence)}</dd></div>
                    <div><dt>Station</dt><dd>{toDisplayText(response.grounding?.station, "Unavailable")}</dd></div>
                    <div><dt>Observed</dt><dd>{formatDateTime(response.grounding?.observedAt)}</dd></div>
                  </div>
                  <p className="uqi-lead">{toDisplayText(response.answer, "No grounded answer returned.")}</p>
                  <div className="uqi-definition-grid">
                    <div><dt>Current AQI</dt><dd>{toDisplayText(response.grounding?.currentAqi, "Unavailable")}</dd></div>
                    <div><dt>AQI Standard</dt><dd>{toDisplayText(response.grounding?.aqiStandard, "Unavailable")}</dd></div>
                    <div><dt>Provider</dt><dd>{toDisplayText(response.grounding?.provider, "Unavailable")}</dd></div>
                    <div><dt>Intent</dt><dd>{labelize(response.intent)}</dd></div>
                  </div>
                  {responseStatus !== "SUCCESS" && (
                    <button className="uqi-button uqi-button--secondary" type="button" disabled={loading || !latestQuestion.trim()} onClick={retryLatest}>
                      Retry
                    </button>
                  )}
                  <details className="uqi-details" open>
                    <summary>Citations and limitations</summary>
                    <ul className="uqi-list">
                      {asArray(response.citations).slice(0, 5).map((citation, index) => (
                        <li key={index}>{toDisplayText(citation.label, "Citation")}: {toDisplayText(citation.value)}</li>
                      ))}
                      {asArray(response.limitations).map((item, index) => <li key={`limit-${index}`}>{toDisplayText(item)}</li>)}
                    </ul>
                  </details>
                </div>
              )}
            </div>
            <div className="uqi-copilot-input">
              <textarea
                ref={inputRef}
                value={question}
                maxLength={500}
                placeholder="Ask about sources, confidence, forecast reliability, advisories, or enforcement priority."
                onChange={(event) => setQuestion(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    submitQuestion(question);
                  }
                }}
              />
              <div>
                <span>{question.trim().length}/500</span>
                <button className="uqi-button" type="button" disabled={loading || !question.trim()} onClick={() => submitQuestion(question)}>
                  {loading ? "Thinking" : "Ask"}
                </button>
                <button className="uqi-button uqi-button--ghost" type="button" onClick={() => setOpen(false)}>Close</button>
              </div>
            </div>
          </aside>
        </>
      )}
    </div>
  );

  return typeof document === "undefined" ? copilotNode : createPortal(copilotNode, document.body);
}

function copilotStatus(response) {
  const status = String(response?.status || "").trim().toUpperCase();
  if (["SUCCESS", "PARTIAL", "UNAVAILABLE"].includes(status)) return status;
  if (!response || !response.answer) return "UNAVAILABLE";
  if (response.degradedMode || asArray(response.limitations).length > 0) return "PARTIAL";
  return "SUCCESS";
}

function copilotModeLabel(response) {
  return String(response?.mode || "").trim().toUpperCase() === "GEMINI" ? "Gemini" : "Grounded fallback";
}

function copilotModeTone(response) {
  return String(response?.mode || "").trim().toUpperCase() === "GEMINI" ? "tone-low" : "tone-neutral";
}
