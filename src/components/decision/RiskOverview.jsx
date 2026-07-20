import { formatPercent, formatScore, getAqiTone, getRiskTone, labelize, toDisplayText } from "./decisionUtils";

export default function RiskOverview({ decision }) {
  const summary = decision?.summary || {};
  const risk = decision?.riskAssessment || {};
  const currentAqi = decision?.currentAQI;
  const signals = decision?.environmentalSignals || {};
  const canonicalAqi = signals.canonicalAqi ?? currentAqi;
  const aqiSourceType = signals.aqiSourceType;
  const aqiSourceLabel = signals.aqiSourceLabel;
  const stationName = signals.stationName;
  const stationDistanceKm = signals.stationDistanceKm;
  const observedAt = signals.observedAt || signals.lastUpdated || signals.providerTimestamp;
  const fetchedAt = signals.fetchedAt || decision?.generatedAt;
  const prominentPollutant = signals.prominentPollutant;
  const primaryPollutant = signals.primaryPollutant || prominentPollutant;
  const openWeatherIndex = signals.openWeatherAqiIndex;
  const openWeatherCategory = signals.openWeatherAqiCategory;
  const isFallback = Boolean(signals.isFallback || signals.fallbackUsed);
  const fallbackReason = signals.fallbackReason || signals.unavailableReason;
  const providerLocation = signals.providerReturnedCity || signals.providerReturnedStation;
  const aqiAvailable = Boolean(decision?.environmentalSignals?.aqiAvailable) || Number(currentAqi) > 0;
  const riskTone = getRiskTone(risk.overallRiskLevel);
  const aqiTone = getAqiTone(Number(canonicalAqi) || 0);
  const hasStationDistance = stationDistanceKm !== null && stationDistanceKm !== undefined && stationDistanceKm !== "";
  const stationDistance = hasStationDistance && Number(stationDistanceKm) >= 0
    ? `${Number(stationDistanceKm).toFixed(3)} km`
    : labelize(signals.freshnessStatus);
  const observedTimestamp = observedAt
    ? new Date(observedAt).toISOString().replace(".000Z", "Z")
    : "Unavailable";

  return (
    <section className="decision-overview">
      <article className={`decision-aqi-card tone-${aqiTone}`}>
        <span className="decision-eyebrow">Current AQI</span>
        <div className={`decision-aqi-value ${aqiAvailable ? "" : "is-unavailable"}`}>{aqiAvailable ? formatScore(canonicalAqi) : "Unavailable"}</div>
        <div className="decision-aqi-meta">
          <span>{toDisplayText(aqiSourceLabel, labelize(aqiSourceType, "Official station AQI unavailable"))}</span>
          <strong>{formatPercent(decision?.overallConfidence)}</strong>
        </div>
        <div className="decision-aqi-meta">
          <span>{toDisplayText(signals.aqiStandard, "AQI standard unavailable")}</span>
          <strong>{labelize(signals.aqiCategory)}</strong>
        </div>
        <div className="decision-aqi-meta">
          <span>{toDisplayText(stationName, providerLocation || signals.aqiProvider || "Provider unavailable")}</span>
          <strong>{stationDistance}</strong>
        </div>
        {isFallback && (
          <div className="decision-aqi-meta">
            <span>Fallback</span>
            <strong>{toDisplayText(fallbackReason, "CPCB unavailable")}</strong>
          </div>
        )}
        <div className="decision-aqi-meta">
          <span>{primaryPollutant ? `Primary pollutant: ${primaryPollutant}` : labelize(signals.freshnessStatus)}</span>
          <strong>{observedTimestamp}</strong>
        </div>
        <div className="decision-aqi-meta">
          <span>OpenWeather separate index</span>
          <strong>{openWeatherIndex ? `${openWeatherIndex}/5 ${labelize(openWeatherCategory, "")}`.trim() : "Unavailable"}</strong>
        </div>
        <div className="decision-aqi-meta">
          <span>CPCB: Indian NAQI · IQAir: US AQI</span>
          <strong>OpenWeather: 1-5</strong>
        </div>
        <div className="decision-aqi-meta">
          <span>Snapshot</span>
          <strong>{toDisplayText(decision?.snapshotId, "Unavailable")}</strong>
        </div>
        <div className="decision-aqi-meta">
          <span>Fetched / status</span>
          <strong>{toDisplayText(fetchedAt, "Unavailable")} / {labelize(signals.freshnessStatus, "Unavailable")}</strong>
        </div>
        <div className="decision-aqi-meta">
          <span>Secondary pollutants</span>
          <strong>{toDisplayText(signals.cpcbPollutantsSource, "Unavailable")}</strong>
        </div>
        {signals.citySummary?.available && (
          <>
            <div className="decision-aqi-meta" style={{ borderTop: "1px solid rgba(255,255,255,0.08)", paddingTop: 6, marginTop: 4 }}>
              <span>City median AQI ({signals.citySummary.freshStationCount} stations)</span>
              <strong className={`tone-text-${getAqiTone(signals.citySummary.medianAqi)}`}>{formatScore(signals.citySummary.medianAqi)}</strong>
            </div>
            <div className="decision-aqi-meta">
              <span>City AQI range</span>
              <strong>{signals.citySummary.stationAqiRange?.min}–{signals.citySummary.stationAqiRange?.max}</strong>
            </div>
          </>
        )}
        {signals.sourceScope && (
          <div className="decision-aqi-meta">
            <span>Scope</span>
            <strong>{labelize(signals.sourceScope)}</strong>
          </div>
        )}
      </article>

      <article className={`decision-risk-card tone-${riskTone}`}>
        <div>
          <span className="decision-eyebrow">Overall Risk</span>
          <h2>{labelize(risk.overallRiskLevel, "Unknown")}</h2>
        </div>
        <div className="decision-risk-grid">
          <span>Forecast</span>
          <strong>{labelize(risk.forecastRisk)}</strong>
          <span>Source</span>
          <strong>{labelize(risk.dominantSourceRisk)}</strong>
          <span>Exposure</span>
          <strong>{labelize(risk.populationExposureRisk)}</strong>
        </div>
      </article>

      <article className="decision-summary-panel">
        <div className="decision-summary-row">
          <span>What is happening</span>
          <p>{toDisplayText(summary.whatIsHappening, "Current AQI intelligence is being assembled.")}</p>
        </div>
        <div className="decision-summary-row">
          <span>Why</span>
          <p>{toDisplayText(summary.whyIsItHappening, "The platform has not supplied a cause summary yet.")}</p>
        </div>
        <div className="decision-summary-row">
          <span>Next</span>
          <p>{toDisplayText(summary.whatWillHappenNext, "Forecast narrative unavailable.")}</p>
        </div>
      </article>
    </section>
  );
}
