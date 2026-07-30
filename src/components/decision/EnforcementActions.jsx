import { asArray, formatPercent, formatScore, labelize, toDisplayText } from "./decisionUtils";
import { enumLabel } from "../../services/decisionNormalization";

export default function EnforcementActions({ enforcement, priorityActions }) {
  const recommendations = asArray(enforcement?.recommendations).slice(0, 7);
  const actions = asArray(priorityActions).slice(0, 5);

  return (
    <section className="decision-panel">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Government Action</span>
          <h2>Priority Enforcement Actions</h2>
        </div>
      </div>

      {recommendations.length === 0 ? (
        <div className="decision-empty-line">No enforcement recommendations returned.</div>
      ) : (
        <div className="decision-action-list">
          {recommendations.map((item) => (
            <article key={item.recommendationId || item.actionType} className="decision-action-card">
              <div className="decision-action-card__score">
                <strong>{formatScore(item.priorityScore ?? item.priority)}</strong>
                <span>{labelize(item.priorityLevel, "Priority")}</span>
              </div>
              <div className="decision-action-card__body">
                <div className="decision-action-card__title">
                  <h3>{item.title || item.actionLabel || enumLabel(item.actionType, "Enforcement action")}</h3>
                  <span>{formatPercent(item.confidence)}</span>
                </div>
                <p>{toDisplayText(item.reason, "No reason returned.")}</p>
                <div className="decision-meta-grid">
                  <span>Agency</span>
                  <strong>{item.agencyStatus === "NOT_REQUIRED" ? "Air Quality Command Center" : item.responsibleAgency || "Agency assignment pending"}</strong>
                  <span>Target</span>
                  <strong>{toDisplayText(item.targetArea || item.wardId, "Citywide")}</strong>
                  <span>Urgency</span>
                  <strong>{labelize(item.urgency)}</strong>
                  <span>Window</span>
                  <strong>{toDisplayText(item.actionWindow, "Unspecified")}</strong>
                  <span>Forecast</span>
                  <strong>{enumLabel(item.forecastEngine, "Forecast evidence not available")}</strong>
                </div>
                <div className="decision-tag-row">
                  {asArray(item.datasetsUsed).map((dataset) => (
                    <span key={dataset}>{labelize(dataset)}</span>
                  ))}
                  {item.snapshotId ? <span>Snapshot {toDisplayText(item.snapshotId)}</span> : null}
                  <span>{asArray(item.supportingEvidence || item.evidence).length} evidence</span>
                </div>
                {asArray(item.recommendedActions).length > 0 ? (
                  <ul className="decision-evidence-list">
                    {asArray(item.recommendedActions).slice(0, 3).map((action, index) => (
                      <li key={`${toDisplayText(action, "action")}-${index}`}>{toDisplayText(action, "Recommended action unavailable")}</li>
                    ))}
                  </ul>
                ) : null}
                {asArray(item.limitations).length > 0 ? (
                  <p className="decision-panel__note">
                    Limitations: {asArray(item.limitations).map((limitation) => toDisplayText(limitation)).join("; ")}
                  </p>
                ) : null}
              </div>
            </article>
          ))}
        </div>
      )}

      {actions.length > 0 && (
        <div className="decision-priority-strip">
          {actions.map((action) => (
            <div key={`${action.sourceEngine}-${action.actionType}-${toDisplayText(action.message, "message")}`}>
              <span>{labelize(action.sourceEngine)}</span>
              <strong>{toDisplayText(action.message, "Action message unavailable.")}</strong>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
