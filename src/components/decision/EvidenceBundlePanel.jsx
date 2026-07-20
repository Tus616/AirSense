import { asArray, formatPercent, labelize, toDisplayText } from "./decisionUtils";

export default function EvidenceBundlePanel({ evidenceBundle }) {
  const datasets = asArray(evidenceBundle?.datasetsUsed);
  const explanations = asArray(evidenceBundle?.explanations);
  const confidenceScores = evidenceBundle?.confidenceScores || {};
  const providerStatus = evidenceBundle?.providerStatus || {};

  return (
    <section className="decision-panel">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Evidence Bundle</span>
          <h2>Datasets, Confidence, Status</h2>
        </div>
      </div>

      <div className="decision-evidence-layout">
        <div>
          <h3>Datasets Used</h3>
          <div className="decision-tag-row">
            {datasets.length === 0 ? (
              <span>No datasets listed</span>
            ) : (
              datasets.map((dataset) => <span key={dataset}>{labelize(dataset)}</span>)
            )}
          </div>
        </div>

        <div>
          <h3>Confidence Scores</h3>
          <div className="decision-metric-list">
            {Object.entries(confidenceScores).length === 0 ? (
              <span>No confidence scores returned.</span>
            ) : (
              Object.entries(confidenceScores).map(([key, value]) => (
                <div key={key}>
                  <span>{labelize(key)}</span>
                  <strong>{formatPercent(value)}</strong>
                </div>
              ))
            )}
          </div>
        </div>

        <div>
          <h3>Provider Status</h3>
          <div className="decision-metric-list">
            {Object.entries(providerStatus).length === 0 ? (
              <span>No provider status returned.</span>
            ) : (
              Object.entries(providerStatus).map(([key, value]) => (
                <div key={key}>
                  <span>{labelize(key)}</span>
                  <strong>{labelize(value)}</strong>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      <div className="decision-explanation-list">
        {explanations.slice(0, 6).map((item, index) => (
          <p key={`${toDisplayText(item, "explanation")}-${index}`}>{toDisplayText(item, "Explanation unavailable")}</p>
        ))}
      </div>
    </section>
  );
}
