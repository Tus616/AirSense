import { asArray, formatPercent, labelize, toDisplayText } from "./decisionUtils";
import { enumLabel, normalizeAttribution } from "../../services/decisionNormalization";

export default function AttributionPanel({ attribution, activeDominantSource }) {
  const sources = asArray(attribution?.sources);
  const normalized = normalizeAttribution(attribution);
  const dominantSource = activeDominantSource || normalized.leadingSource?.sourceLabel || attribution?.dominantSource;
  const contributionValue = (source) => {
    const value = source?.estimatedContributionPercent ?? source?.percentage ?? source?.contributionPercent;
    return value == null ? null : Number(value);
  };
  const evidenceText = (item) => {
    if (!item || typeof item !== "object") return toDisplayText(item, "Evidence unavailable");
    const parts = [
      toDisplayText(item.type || item.message, ""),
      item.value != null ? `value: ${toDisplayText(item.value)}${item.unit ? ` ${item.unit}` : ""}` : "",
      item.provider || item.dataset ? `provider: ${labelize(item.provider || item.dataset)}` : "",
      item.signalType ? `signal: ${labelize(item.signalType)}` : "",
      item.limitation ? `limit: ${toDisplayText(item.limitation)}` : "",
    ].filter(Boolean);
    return parts.join(" | ") || "Evidence unavailable";
  };

  return (
    <section className="decision-panel">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Leading source</span>
          <h2>{labelize(dominantSource, "Dominant source unavailable")}</h2>
        </div>
        <span className="decision-chip">{normalized.leadingSource?.contributionText || formatPercent(attribution?.overallConfidence)}</span>
      </div>
      <p className="decision-panel__note">Overall confidence: {normalized.confidenceText}. Total contribution shown: {normalized.totalContribution}%.</p>

      <p className="decision-panel__note">
        {toDisplayText(attribution?.explanation, "Attribution explanation was not included in the decision response.")}
      </p>
      <p className="decision-panel__note">
        Source contributions are model-based estimates derived from pollutant, weather, geospatial, fire and satellite evidence. They are not laboratory source-apportionment measurements.
      </p>
      <p className="decision-panel__note">
        Snapshot: {toDisplayText(attribution?.snapshotId, "unavailable")}
        {" | "}Snapshot ref: {toDisplayText(attribution?.locationKey, "unavailable")}
        {" | "}Location: {toDisplayText(attribution?.locationHash, "unavailable")}
        {" | "}Observed: {toDisplayText(attribution?.snapshotObservedAt, "unavailable")}
        {" | "}Generated: {toDisplayText(attribution?.snapshotGeneratedAt || attribution?.generatedAt || attribution?.timestamp, "unavailable")}
        {" | "}Reused: {attribution?.snapshotReused ? "yes" : "no"}
        {" | "}Provider timestamps: {toDisplayText(attribution?.diagnostics?.providerTimestamps, "unavailable")}
      </p>

      <div className="decision-source-list">
        {sources.length === 0 ? (
          <div className="decision-empty-line">Insufficient evidence. Source attribution is unavailable for this snapshot.</div>
        ) : (
          sources.map((source) => {
            const percent = contributionValue(source);
            return (
            <article key={source.sourceType || source.sourceId} className="decision-source-row">
              <div className="decision-source-row__main">
                <div>
                  <strong>{toDisplayText(source.displayName, labelize(source.sourceType))}</strong>
                  <span>{formatPercent(source.confidence)} confidence{source.confidenceLabel ? ` - ${labelize(source.confidenceLabel)}` : ""}</span>
                </div>
                <b>{percent == null || Number.isNaN(percent) ? "Evidence not available" : `${Math.round(percent)}%`}</b>
              </div>
              <div className="decision-source-bar">
                <span style={{ width: `${Math.max(0, Math.min(100, percent ?? 0))}%` }} />
              </div>
              <div className="decision-tag-row">
                <span>{enumLabel(source.dataOrigin || "DERIVED_FROM_REAL_DATA")}</span>
                <span>{enumLabel(source.dataAvailability || "PARTIAL")}</span>
                {source.signalType && <span>{labelize(source.signalType)}</span>}
                {source.geometrySource && <span>{labelize(source.geometrySource)}</span>}
                <span>{asArray(source.supportingEvidence || source.evidence).length} evidence</span>
                {asArray(source.datasetsUsed).map((dataset) => (
                  <span key={dataset}>{labelize(dataset)}</span>
                ))}
              </div>
              {(source.limitations || source.timestamp) && (
                <p className="decision-panel__note">
                  {source.limitations ? toDisplayText(source.limitations) : ""}
                  {source.timestamp ? ` Timestamp: ${toDisplayText(source.timestamp)}` : ""}
                </p>
              )}
              <ul className="decision-evidence-list">
                {asArray(source.supportingEvidence || source.evidence).slice(0, 3).map((item, index) => (
                  <li key={`${evidenceText(item)}-${index}`}>{evidenceText(item)}</li>
                ))}
              </ul>
              {asArray(source.contradictingEvidence).length > 0 && (
                <p className="decision-panel__note">
                  Contradicting evidence: {asArray(source.contradictingEvidence).slice(0, 2).map(evidenceText).join("; ")}
                </p>
              )}
              {asArray(source.missingEvidence).length > 0 && (
                <p className="decision-panel__note">
                  Missing evidence: {asArray(source.missingEvidence).slice(0, 4).map(labelize).join("; ")}
                </p>
              )}
            </article>
          )})
        )}
      </div>
    </section>
  );
}
