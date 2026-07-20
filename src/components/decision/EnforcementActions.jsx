import { asArray, formatPercent, formatScore, labelize, toDisplayText } from "./decisionUtils";

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
                <strong>{formatScore(item.priorityScore)}</strong>
                <span>{labelize(item.priorityLevel, "Priority")}</span>
              </div>
              <div className="decision-action-card__body">
                <div className="decision-action-card__title">
                  <h3>{labelize(item.actionType)}</h3>
                  <span>{formatPercent(item.confidence)}</span>
                </div>
                <p>{toDisplayText(item.reason, "No reason returned.")}</p>
                <div className="decision-meta-grid">
                  <span>Agency</span>
                  <strong>{item.responsibleAgency || "Unassigned"}</strong>
                  <span>Ward</span>
                  <strong>{item.wardId || "Citywide"}</strong>
                  <span>Urgency</span>
                  <strong>{labelize(item.urgency)}</strong>
                </div>
                <div className="decision-tag-row">
                  {asArray(item.datasetsUsed).map((dataset) => (
                    <span key={dataset}>{labelize(dataset)}</span>
                  ))}
                </div>
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
