import { asArray, formatPercent, getRiskTone, labelize, toDisplayText } from "./decisionUtils";

export default function CitizenAdvisories({ advisoryResult }) {
  const advisories = asArray(advisoryResult?.advisories);

  return (
    <section className="decision-panel">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Citizen Response</span>
          <h2>Health Advisories</h2>
        </div>
      </div>

      {advisories.length === 0 ? (
        <div className="decision-empty-line">No citizen advisories returned.</div>
      ) : (
        <div className="decision-advisory-grid">
          {advisories.slice(0, 9).map((advisory) => (
            <article
              key={advisory.advisoryId || advisory.targetGroup}
              className={`decision-advisory-card tone-${getRiskTone(advisory.severity)}`}
            >
              <div className="decision-advisory-card__top">
                <span>{labelize(advisory.targetGroup)}</span>
                <strong>{labelize(advisory.severity)}</strong>
              </div>
              <h3>{toDisplayText(advisory.title, "Health advisory")}</h3>
              <p>{toDisplayText(advisory.message, "No advisory message returned.")}</p>
              <div className="decision-advisory-card__risk">
                {labelize(advisory.exposureRisk)} risk - {formatPercent(advisory.confidence)}
              </div>
              <ul>
                {asArray(advisory.recommendedActions).slice(0, 3).map((action, index) => (
                  <li key={`${toDisplayText(action, "action")}-${index}`}>{toDisplayText(action, "Recommended action unavailable")}</li>
                ))}
              </ul>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
