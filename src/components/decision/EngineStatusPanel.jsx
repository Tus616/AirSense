import { asArray, labelize, toDisplayText } from "./decisionUtils";

export default function EngineStatusPanel({ engineStatus }) {
  const statusRows = [
    ["Fusion", engineStatus?.fusionStatus],
    ["Attribution", engineStatus?.attributionStatus],
    ["Forecast", engineStatus?.forecastStatus],
    ["Enforcement", engineStatus?.enforcementStatus],
    ["Advisory", engineStatus?.advisoryStatus],
  ];

  return (
    <section className="decision-panel">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Engine Status</span>
          <h2>{engineStatus?.degradedMode ? "Degraded Mode" : "All Engines Reporting"}</h2>
        </div>
        <span className={`decision-chip ${engineStatus?.degradedMode ? "decision-chip--warn" : "decision-chip--ok"}`}>
          {engineStatus?.degradedMode ? "Partial" : "Stable"}
        </span>
      </div>

      <div className="decision-engine-grid">
        {statusRows.map(([name, status]) => (
          <div key={name}>
            <span>{name}</span>
            <strong>{labelize(status, "Unknown")}</strong>
          </div>
        ))}
      </div>

      {asArray(engineStatus?.failureReasons).length > 0 && (
        <ul className="decision-evidence-list">
          {asArray(engineStatus.failureReasons).map((reason, index) => (
            <li key={`${toDisplayText(reason, "reason")}-${index}`}>{toDisplayText(reason, "Failure reason unavailable")}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
