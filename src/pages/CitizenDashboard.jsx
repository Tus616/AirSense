import { useEffect, useMemo, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate } from "react-router-dom";
import { fetchPublicData } from "../store/sensorDataSlice";
import { setCitizenTab } from "../store/uiStateSlice";
import {
  askAssistant,
  getCitizenProfile,
  updateCitizenProfile,
  getCitizenRisk,
  getCitizenNotifications,
} from "../services/api";
import { logout } from "../store/authSlice";
import RoleSwitcher, { BackToRoleSelection } from "../components/layout/RoleSwitcher";

const tabs = [
  ["aqi", "AQI"],
  ["prediction", "Prediction"],
  ["assistant", "Assistant"],
  ["settings", "Settings"],
];

const conditionOptions = ["asthma", "COPD", "elderly", "child", "pregnancy", "outdoorWorker"];
const channelOptions = ["inApp", "sms", "email", "push", "publicDisplay", "ivrScript"];

function display(value, fallback = "Unavailable") {
  return value === null || value === undefined || value === "" ? fallback : String(value);
}

function formatAqi(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? Math.round(numeric) : "Unavailable";
}

function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? display(value) : date.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

function labelize(value) {
  return display(value)
    .replace(/_/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function aqiTone(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "neutral";
  if (numeric <= 50) return "low";
  if (numeric <= 100) return "medium";
  if (numeric <= 200) return "high";
  return "critical";
}

export default function CitizenDashboard() {
  const dispatch = useDispatch();
  const navigate = useNavigate();
  const activeTab = useSelector((state) => state.uiState.citizenTab);
  const { neighborhoods, forecast, healthAdvisory: advisory, citySummary, status, error } = useSelector((state) => state.sensorData);
  const user = useSelector((state) => state.auth.user);

  const [profile, setProfile] = useState({ conditions: [], selectedLanguage: "en", channels: ["inApp"], vulnerable: false, wardId: "W01" });
  const [riskData, setRiskData] = useState(null);
  const [notifications, setNotifications] = useState([]);
  const [savingProfile, setSavingProfile] = useState(false);
  const [question, setQuestion] = useState("");
  const [assistantResponse, setAssistantResponse] = useState(null);
  const [asking, setAsking] = useState(false);

  useEffect(() => {
    if (!citySummary && status !== "loading") {
      dispatch(fetchPublicData());
    }
    getCitizenProfile().then(setProfile).catch(console.error);
    getCitizenRisk().then(setRiskData).catch(console.error);
    getCitizenNotifications().then(setNotifications).catch(console.error);
  }, [dispatch, citySummary, status]);

  const primaryAqi = citySummary?.aqi ?? neighborhoods?.[0]?.aqi;
  const primaryCategory = citySummary?.category || neighborhoods?.[0]?.category || advisory?.riskLevel;
  const primaryStation = citySummary?.stationName || neighborhoods?.[0]?.name || profile.wardId;
  const pollutantRows = useMemo(() => {
    const source = citySummary?.pollutants || neighborhoods?.[0]?.pollutants || {};
    const entries = Object.entries(source).slice(0, 5);
    if (entries.length > 0) return entries;
    return [["Dominant pollutant", neighborhoods?.[0]?.dominantPollutant]];
  }, [citySummary, neighborhoods]);

  async function handleProfileSave(event) {
    event.preventDefault();
    setSavingProfile(true);
    try {
      await updateCitizenProfile(profile);
      setRiskData(await getCitizenRisk());
      setNotifications(await getCitizenNotifications());
      window.alert("Profile updated successfully.");
    } catch (err) {
      console.error(err);
      window.alert("Failed to update profile.");
    } finally {
      setSavingProfile(false);
    }
  }

  function handleConditionToggle(value) {
    setProfile((current) => {
      const conditions = current.conditions.includes(value)
        ? current.conditions.filter((item) => item !== value)
        : [...current.conditions, value];
      return { ...current, conditions, vulnerable: conditions.length > 0 };
    });
  }

  function handleChannelToggle(value) {
    setProfile((current) => {
      const channels = current.channels.includes(value)
        ? current.channels.filter((item) => item !== value)
        : [...current.channels, value];
      return { ...current, channels };
    });
  }

  async function handleAsk(event) {
    event.preventDefault();
    if (!question.trim()) return;
    setAsking(true);
    try {
      setAssistantResponse(await askAssistant(question));
    } catch (err) {
      console.error(err);
    } finally {
      setAsking(false);
    }
  }

  function signOut() {
    dispatch(logout());
    navigate("/login");
  }

  if (status === "loading" && !citySummary) {
    return <CitizenFrame user={user} onLogout={signOut}><div className="citizen-state">Loading your air quality dashboard...</div></CitizenFrame>;
  }

  if (status === "failed") {
    return <CitizenFrame user={user} onLogout={signOut}><div className="citizen-state is-error"><h2>Error loading data</h2><p>{error}</p></div></CitizenFrame>;
  }

  return (
    <CitizenFrame user={user} onLogout={signOut}>
      <section className="citizen-hero">
        <div>
          <span className="citizen-eyebrow">Citizen Dashboard</span>
          <h1>{display(user?.name?.split(" ")[0], "Your")} air quality briefing</h1>
          <p>Current public AQI, predictions, advisories, and personalized risk settings in one clear view.</p>
        </div>
        <div className={`citizen-aqi-orb tone-${aqiTone(primaryAqi)}`}>
          <span>Current AQI</span>
          <strong>{formatAqi(primaryAqi)}</strong>
          <small>{labelize(primaryCategory)}</small>
        </div>
      </section>

      <nav className="citizen-tabs" aria-label="Citizen dashboard sections">
        {tabs.map(([id, label]) => (
          <button key={id} type="button" className={activeTab === id ? "is-active" : ""} onClick={() => dispatch(setCitizenTab(id))}>
            {label}
          </button>
        ))}
      </nav>

      {activeTab === "aqi" && (
        <div className="citizen-page-grid">
          <section className="citizen-card citizen-card--primary">
            <div className="citizen-card__header">
              <h2>Current Local AQI</h2>
              <span className={`citizen-badge tone-${aqiTone(primaryAqi)}`}>{labelize(primaryCategory)}</span>
            </div>
            <div className={`citizen-primary-aqi ${Number.isFinite(Number(primaryAqi)) ? "" : "is-unavailable"}`}>{formatAqi(primaryAqi)}</div>
            <p>{display(advisory?.message || advisory?.advisory || riskData?.topLine, "No health recommendation was returned.")}</p>
            <dl className="citizen-definition-grid">
              <div><dt>Nearby station</dt><dd>{display(primaryStation)}</dd></div>
              <div><dt>Ward</dt><dd>{display(profile.wardId)}</dd></div>
              <div><dt>Personal risk</dt><dd>{display(riskData?.riskLevel)}</dd></div>
              <div><dt>Notifications</dt><dd>{notifications.length}</dd></div>
            </dl>
          </section>

          <section className="citizen-card">
            <div className="citizen-card__header"><h2>Pollutants</h2><span className="citizen-badge">Live</span></div>
            <div className="citizen-pollutants">
              {pollutantRows.map(([key, value]) => (
                <div key={key}>
                  <span>{labelize(key)}</span>
                  <strong>{display(value)}</strong>
                </div>
              ))}
            </div>
          </section>

          <section className="citizen-card citizen-card--wide">
            <div className="citizen-card__header"><h2>Neighborhoods</h2><span className="citizen-badge">{neighborhoods.length} areas</span></div>
            {neighborhoods.length === 0 ? <CitizenEmpty text="No neighborhood AQI rows were returned." /> : (
              <div className="citizen-neighborhood-grid">
                {neighborhoods.map((item) => (
                  <article className={`citizen-mini-card tone-${aqiTone(item.aqi)}`} key={item.id || item.name}>
                    <span>{display(item.name)}</span>
                    <strong>{formatAqi(item.aqi)}</strong>
                    <small>{labelize(item.category || item.dominantPollutant)}</small>
                  </article>
                ))}
              </div>
            )}
          </section>
        </div>
      )}

      {activeTab === "prediction" && (
        <div className="citizen-page-grid">
          <section className="citizen-card citizen-card--wide">
            <div className="citizen-card__header"><h2>3-Day Forecast</h2><span className="citizen-badge">{forecast.length} days</span></div>
            {forecast.length === 0 ? <CitizenEmpty text="Forecast is unavailable for this profile." /> : (
              <div className="citizen-forecast-grid">
                {forecast.map((item) => (
                  <article className={`citizen-mini-card tone-${aqiTone(item.aqi)}`} key={item.date || item.day}>
                    <span>{formatDate(item.date || item.day)}</span>
                    <strong>{formatAqi(item.aqi)}</strong>
                    <small>{display(item.low)} - {display(item.high)} AQI range</small>
                    <p>{display(item.advisory || item.category)}</p>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="citizen-card">
            <div className="citizen-card__header"><h2>Risk Summary</h2><span className={`citizen-badge tone-${aqiTone(riskData?.personalRiskScore)}`}>{display(riskData?.riskLevel)}</span></div>
            <dl className="citizen-definition-grid">
              <div><dt>Ward risk</dt><dd>{display(riskData?.wardRiskScore)}/100</dd></div>
              <div><dt>Personal risk</dt><dd>{display(riskData?.personalRiskScore)}/100</dd></div>
              <div><dt>Peak AQI</dt><dd>{display(riskData?.forecastPeakAqi)}</dd></div>
              <div><dt>Peak time</dt><dd>{display(riskData?.forecastPeakAt)}</dd></div>
            </dl>
            <p>{display(riskData?.advisory, "No personalized risk advisory was returned.")}</p>
          </section>

          <section className="citizen-card citizen-card--wide">
            <div className="citizen-card__header"><h2>Recent Notifications</h2><span className="citizen-badge">{notifications.length} items</span></div>
            {notifications.length === 0 ? <CitizenEmpty text="No notifications have been returned yet." /> : (
              <div className="citizen-notification-list">
                {notifications.map((item) => (
                  <article key={item.id || item.generatedAt}>
                    <strong>{display(item.channel)} - {labelize(item.riskCategory)}</strong>
                    <span>{formatDate(item.generatedAt)}</span>
                    <p>{display(item.message)}</p>
                  </article>
                ))}
              </div>
            )}
          </section>
        </div>
      )}

      {activeTab === "assistant" && (
        <section className="citizen-card citizen-assistant-card">
          <div className="citizen-card__header">
            <h2>Ask the Health Assistant</h2>
            <span className="citizen-badge">Grounded</span>
          </div>
          <p>Ask about outdoor activity, vulnerable groups, notifications, or your current risk profile.</p>
          <form className="citizen-assistant-form" onSubmit={handleAsk}>
            <input value={question} onChange={(event) => setQuestion(event.target.value)} placeholder="Is it safe to go for a run right now?" />
            <button type="submit" disabled={asking || !question.trim()}>{asking ? "Thinking..." : "Ask"}</button>
          </form>
          <div className="citizen-prompt-row">
            {["What should children do today?", "Explain my risk score", "Should I wear a mask?"].map((prompt) => (
              <button type="button" key={prompt} onClick={() => setQuestion(prompt)}>{prompt}</button>
            ))}
          </div>
          {assistantResponse ? (
            <article className="citizen-assistant-response">
              <p>{display(assistantResponse.answer)}</p>
              <dl className="citizen-definition-grid">
                <div><dt>Grounding</dt><dd>{display(assistantResponse.groundingSummary)}</dd></div>
                <div><dt>Safety note</dt><dd>{display(assistantResponse.safetyNotes)}</dd></div>
              </dl>
              {assistantResponse.isSimulated && <span className="citizen-badge tone-medium">Offline fallback</span>}
            </article>
          ) : <CitizenEmpty text="Ask a question to see an evidence-backed response." />}
        </section>
      )}

      {activeTab === "settings" && (
        <form className="citizen-settings-layout" onSubmit={handleProfileSave}>
          <CitizenSettingsSection title="Health Vulnerabilities" note="Select any that apply to receive tailored risk advisories.">
            <div className="citizen-check-grid">
              {conditionOptions.map((condition) => (
                <CitizenCheckbox
                  key={condition}
                  label={labelize(condition)}
                  checked={profile.conditions.includes(condition)}
                  onChange={() => handleConditionToggle(condition)}
                />
              ))}
            </div>
          </CitizenSettingsSection>

          <CitizenSettingsSection title="Language" note="Choose the preferred language for public guidance.">
            <select className="citizen-select" value={profile.selectedLanguage} onChange={(event) => setProfile({ ...profile, selectedLanguage: event.target.value })}>
              <option value="en">English</option>
              <option value="hi">Hindi</option>
              <option value="kn">Kannada</option>
              <option value="ta">Tamil</option>
            </select>
          </CitizenSettingsSection>

          <CitizenSettingsSection title="Delivery Channels" note="Choose where severe-risk notifications should be prepared.">
            <div className="citizen-check-grid">
              {channelOptions.map((channel) => (
                <CitizenCheckbox
                  key={channel}
                  label={labelize(channel)}
                  checked={profile.channels.includes(channel)}
                  disabled={channel === "inApp"}
                  onChange={() => handleChannelToggle(channel)}
                />
              ))}
            </div>
          </CitizenSettingsSection>

          <button className="citizen-save-button" type="submit" disabled={savingProfile}>{savingProfile ? "Saving..." : "Save Risk Profile"}</button>
        </form>
      )}
    </CitizenFrame>
  );
}

function CitizenFrame({ user, onLogout, children }) {
  return (
    <div className="citizen-shell">
      <header className="citizen-topbar">
        <div className="citizen-brand">
          <div className="citizen-brand__mark" aria-hidden="true">AQ</div>
          <div>
            <strong>AirSense</strong>
            <span>Public dashboard</span>
          </div>
        </div>
        <BackToRoleSelection />
        <RoleSwitcher active="citizen" compact />
        <div className="citizen-user">
          <span>{display(user?.name || user?.email, "Citizen")}</span>
          <small>Citizen</small>
          <button type="button" onClick={onLogout}>Logout</button>
        </div>
      </header>
      <main className="citizen-main">{children}</main>
    </div>
  );
}

function CitizenEmpty({ text }) {
  return <div className="citizen-empty">{text}</div>;
}

function CitizenCheckbox({ label, checked, onChange, disabled = false }) {
  return (
    <label className="citizen-checkbox">
      <input type="checkbox" checked={checked} onChange={onChange} disabled={disabled} />
      <span aria-hidden="true" />
      <strong>{label}</strong>
    </label>
  );
}

function CitizenSettingsSection({ title, note, children }) {
  return (
    <section className="citizen-card citizen-settings-section">
      <div>
        <h2>{title}</h2>
        <p>{note}</p>
      </div>
      {children}
    </section>
  );
}
