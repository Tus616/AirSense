import { formatPercent, formatScore, getAqiTone, getForecastPoints, labelize, toDisplayText } from "./decisionUtils";

function forecastStatus(point, forecastResult) {
  const mode = point?.mode || forecastResult?.mode || "";
  if (mode.startsWith("ML_") && point?.modelPromotionStatus === "PROMOTED") return "Validated ML model";
  if (mode === "TREND_WEATHER_V1") return "Trend-weather heuristic";
  if (mode === "PERSISTENCE") return "Persistence baseline";
  if (mode === "UNAVAILABLE" || point?.predictedAqi === null || point?.predictedAqi === undefined) return "Unavailable";
  return labelize(mode, "Unavailable");
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
  };
  return labels[reason] || labelize(reason, "Fallback reason unavailable");
}

export default function ForecastStrip({ forecastResult, activeHorizon }) {
  const points = getForecastPoints(forecastResult);
  const forecastMode = forecastResult?.mode
    ? forecastResult.mode
    : forecastResult?.modelVersion
      ? forecastResult.modelVersion
      : forecastResult?.fallbackUsed
        ? "UNAVAILABLE"
        : "unavailable";

  return (
    <section className="decision-panel">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Forecast Engine</span>
          <h2>24h / 48h / 72h Outlook</h2>
        </div>
        <span className="decision-chip">{labelize(forecastMode, "Trend unavailable")}</span>
      </div>
      <p className="decision-panel__note">
        Snapshot: {toDisplayText(forecastResult?.snapshotId, "unavailable")}
        {" | "}Location: {toDisplayText(forecastResult?.locationHash, "unavailable")}
        {" | "}Generated: {toDisplayText(forecastResult?.generatedAt, "unavailable")}
        {" | "}Standard: {labelize(forecastResult?.forecastStandard, "UNAVAILABLE")}
        {" | "}Provider: {labelize(forecastResult?.currentProvider, "UNAVAILABLE")}
        {" | "}Status: {labelize(forecastMode, "UNAVAILABLE")}
        {" | "}Confidence: {formatPercent(forecastResult?.overallConfidence)}
        {forecastResult?.stationKey ? ` | Station: ${forecastResult.stationKey}` : ""}
      </p>
      <p className="decision-panel__note" style={{ color: "var(--accent-orange)", fontWeight: 500 }}>
        Disclaimer: This forecast represents the selected monitoring station ({toDisplayText(forecastResult?.stationName || points[0]?.stationName, "Unknown Station")}) and is not a city-wide average.
      </p>
      {Array.isArray(forecastResult?.warnings) && forecastResult.warnings.length > 0 ? (
        <p className="decision-panel__note">
          Warnings: {forecastResult.warnings.map((warning) => labelize(warning)).join(", ")}
        </p>
      ) : null}

      <div className="decision-forecast-grid">
        {points.map((point) => {
          const unavailable = point.predictedAqi === null || point.predictedAqi === undefined || point.predictedAqi === "";
          const baseline = forecastResult?.baseline?.[point.key];
          const mode = point.mode
            || point.modelVersion
            || forecastResult?.mode
            || forecastResult?.modelVersion
            || "UNAVAILABLE";
          return (
            <article
              key={point.key}
              className={`decision-forecast-card tone-${getAqiTone(point.predictedAqi)} ${
                Number(activeHorizon) === Number(point.horizonHours) ? "is-active" : ""
              }`}
            >
              <div className="decision-forecast-card__top">
                <strong>{point.key}</strong>
                <span>{forecastStatus(point, forecastResult)}</span>
              </div>
              <div className="decision-forecast-aqi">{formatScore(point.predictedAqi)}</div>
              <div className="decision-forecast-range">
                {point.lowerBound === null || point.lowerBound === undefined || point.upperBound === null || point.upperBound === undefined
                  ? "Range unavailable"
                  : `${formatScore(point.lowerBound)}-${formatScore(point.upperBound)}`}
              </div>
              <p>
                Confidence: {formatPercent(point.confidence)}
                {point.confidenceLabel ? ` / ${labelize(point.confidenceLabel)}` : ""}
              </p>
              <dl>
                <div>
                  <dt>Category</dt>
                  <dd>{labelize(point.aqiCategory)}</dd>
                </div>
                <div>
                  <dt>Source</dt>
                  <dd>{labelize(point.expectedDominantSource)}</dd>
                </div>
                <div>
                  <dt>Health</dt>
                  <dd>{labelize(point.healthRiskLevel)}</dd>
                </div>
                <div>
                  <dt>Mode</dt>
                  <dd>{labelize(mode, "Unavailable")}</dd>
                </div>
                <div>
                  <dt>Scope</dt>
                  <dd>{labelize(point.forecastScope, "Unavailable")}</dd>
                </div>
                <div>
                  <dt>Family</dt>
                  <dd>{labelize(point.modelFamily, "Unavailable")}</dd>
                </div>
                <div>
                  <dt>Model</dt>
                  <dd>{toDisplayText(point.modelVersion, "Unavailable")}</dd>
                </div>
                <div>
                  <dt>Promotion</dt>
                  <dd>{labelize(point.modelPromotionStatus || point.fallbackReason, "Not promoted")}</dd>
                </div>
                <div>
                  <dt>Standard</dt>
                  <dd>{labelize(point.aqiStandard || forecastResult?.forecastStandard, "Unavailable")}</dd>
                </div>
                <div>
                  <dt>Target</dt>
                  <dd>{point.targetTime ? new Date(point.targetTime).toLocaleString() : "Unavailable"}</dd>
                </div>
                <div>
                  <dt>Window</dt>
                  <dd>{toDisplayText(point.dataWindow, "Unavailable")}</dd>
                </div>
                <div>
                  <dt>History</dt>
                  <dd>
                    {toDisplayText(point.validObservationCount, "0")} observations / {toDisplayText(point.coverageHours, "0")} hours
                  </dd>
                </div>
                <div>
                  <dt>Baseline</dt>
                  <dd>{baseline ? formatScore(baseline.predictedAqi) : formatScore(point.baselinePredictedAqi)}</dd>
                </div>
                <div>
                  <dt>Validation</dt>
                  <dd>
                    {point.validationRmse || point.baselineRmse
                      ? `RMSE ${toDisplayText(point.validationRmse, "n/a")} / baseline ${toDisplayText(point.baselineRmse, "n/a")}`
                      : "Unavailable"}
                  </dd>
                </div>
                <div>
                  <dt>Origin</dt>
                  <dd>{labelize(point.dataOrigin, "Unavailable")}</dd>
                </div>
                {point.featureCoveragePercent != null ? (
                  <div>
                    <dt>Features</dt>
                    <dd>{formatPercent(point.featureCoveragePercent / 100)} coverage</dd>
                  </div>
                ) : null}
                {point.promotionStatus ? (
                  <div>
                    <dt>Status</dt>
                    <dd>{labelize(point.promotionStatus)}</dd>
                  </div>
                ) : null}
              </dl>
              <p>
                {unavailable
                  ? "Forecast unavailable because no trustworthy current AQI was returned."
                  : toDisplayText(point.explanation || point.meteorologicalInfluence, "No forecast explanation returned.")}
              </p>
              {Array.isArray(point.insufficiencyReasons) && point.insufficiencyReasons.length > 0 ? (
                <p>Internal checks: {point.insufficiencyReasons.map((reason) => labelize(reason)).join(", ")}</p>
              ) : null}
              {point.fallbackReason ? <p>Fallback reason: {fallbackReasonLabel(point.fallbackReason)}</p> : null}
              {Array.isArray(point.drivers) && point.drivers.length > 0 ? (
                <p>{point.drivers.map((driver) => `${labelize(driver.factor)}: ${toDisplayText(driver.evidence)}`).join(" | ")}</p>
              ) : null}
            </article>
          );
        })}
      </div>
    </section>
  );
}
