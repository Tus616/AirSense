import { useEffect, useState } from "react";
import { getExplainability } from "../../services/decisionApi";
import { asArray, formatPercent, labelize, toDisplayText } from "./decisionUtils";

function objectEntries(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? Object.entries(value) : [];
}

function formatTimestamp(value) {
  if (!value) return "Unavailable";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Unavailable";
  return date.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

export default function ExplainabilityPanel({ city, cityId = "", summary }) {
  const [expanded, setExpanded] = useState(false);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [requestKey, setRequestKey] = useState(0);

  useEffect(() => {
    setData(null);
    setError("");
    setExpanded(false);
  }, [city, cityId]);

  useEffect(() => {
    if (!expanded || data) return;

    const controller = new AbortController();
    const fetchExplainability = async () => {
      setLoading(true);
      setError("");
      try {
        const result = await getExplainability(city || cityId, { signal: controller.signal });
        setData(result && typeof result === "object" ? result : null);
      } catch (err) {
        if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
        setError(err?.response?.data?.message || err?.message || "Explainability request failed.");
      } finally {
        setLoading(false);
      }
    };

    fetchExplainability();
    return () => {
      controller.abort();
    };
  }, [city, cityId, data, expanded, requestKey]);

  const confidenceRows = objectEntries(data?.confidenceBreakdown);
  const reasoning = asArray(data?.reasoning);
  const evidence = asArray(data?.evidence);
  const datasets = asArray(data?.datasets);
  const limitations = asArray(data?.limitations);
  const providerStatus = objectEntries(data?.providerStatus);

  return (
    <section className="decision-panel decision-explainability">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Explainability</span>
          <h2>Why did the AI make this decision?</h2>
        </div>
        <button className="decision-button decision-button--compact" type="button" onClick={() => setExpanded((value) => !value)}>
          {expanded ? "Hide" : "Show"}
        </button>
      </div>

      <p className="decision-panel__note">
        {toDisplayText(
          summary?.explainabilityScore !== undefined
            ? `Explainability score ${formatPercent(summary.explainabilityScore)} with ${summary.evidenceCount || 0} evidence items.`
            : "Open this panel to inspect confidence, evidence, reasoning, and provider health.",
          "Explainability summary unavailable."
        )}
      </p>

      {expanded && (
        <div className="decision-explainability__body">
          {loading && <p className="decision-panel__note">Loading explanation evidence.</p>}
          {error && (
            <div className="decision-inline-error">
              <span>{error}</span>
              <button
                className="decision-button decision-button--compact"
                type="button"
                onClick={() => {
                  setError("");
                  setData(null);
                  setRequestKey((value) => value + 1);
                }}
              >
                Retry
              </button>
            </div>
          )}

          {!loading && !error && data && (
            <>
              <div className="decision-explainability__summary">
                <strong>{toDisplayText(data.explanation, "No narrative explanation returned.")}</strong>
                <span>Generated: {formatTimestamp(data.generatedAt)}</span>
                <span>Snapshot: {toDisplayText(data.snapshotId, "Unavailable")}</span>
                <span>Location: {toDisplayText(data.locationHash, "Unavailable")}</span>
              </div>

              <div className="decision-evidence-layout">
                <div>
                  <h3>Confidence Breakdown</h3>
                  <div className="decision-metric-list">
                    {confidenceRows.length === 0 ? (
                      <span>No confidence breakdown returned.</span>
                    ) : (
                      confidenceRows.map(([key, value]) => (
                        <div key={key}>
                          <span>{labelize(key)}</span>
                          <strong>{formatPercent(value)}</strong>
                        </div>
                      ))
                    )}
                  </div>
                </div>

                <div>
                  <h3>Data Sources Used</h3>
                  <div className="decision-tag-row">
                    {datasets.length === 0 ? (
                      <span>No datasets listed.</span>
                    ) : (
                      datasets.map((dataset) => <span key={dataset}>{labelize(dataset)}</span>)
                    )}
                  </div>
                </div>

                <div>
                  <h3>Provider Health</h3>
                  <div className="decision-metric-list">
                    {providerStatus.length === 0 ? (
                      <span>No provider status returned.</span>
                    ) : (
                      providerStatus.map(([key, value]) => (
                        <div key={key}>
                          <span>{labelize(key)}</span>
                          <strong>{labelize(value)}</strong>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </div>

              <div>
                <h3>Reasoning Timeline</h3>
                <ol className="decision-reasoning-list">
                  {reasoning.length === 0 ? (
                    <li>No reasoning steps returned.</li>
                  ) : (
                    reasoning.map((step, index) => (
                      <li key={`${toDisplayText(step.stage, "step")}-${index}`}>
                        <span>{labelize(step.stage)}</span>
                        <strong>{toDisplayText(step.statement, "Reasoning unavailable.")}</strong>
                        <small>{formatPercent(step.confidence)} confidence</small>
                      </li>
                    ))
                  )}
                </ol>
              </div>

              <div>
                <h3>Evidence Table</h3>
                <div className="decision-evidence-table" role="table" aria-label="Explainability evidence">
                  <div role="row">
                    <span>Dataset</span>
                    <span>Signal</span>
                    <span>Weight</span>
                    <span>Confidence</span>
                    <span>Provider</span>
                  </div>
                  {evidence.length === 0 ? (
                    <p>No evidence items returned.</p>
                  ) : (
                    evidence.slice(0, 12).map((item, index) => (
                      <div role="row" key={`${toDisplayText(item.signal, "signal")}-${index}`}>
                        <span>{labelize(item.dataset)}</span>
                        <span>{toDisplayText(item.signal, "Signal unavailable.")}</span>
                        <span>{formatPercent(item.weight)}</span>
                        <span>{formatPercent(item.confidence)}</span>
                        <span>{labelize(item.provider)}</span>
                      </div>
                    ))
                  )}
                </div>
              </div>

              <div>
                <h3>Limitations</h3>
                <ul className="decision-evidence-list">
                  {limitations.length === 0 ? (
                    <li>No limitations reported by the explainability engine.</li>
                  ) : (
                    limitations.map((item, index) => (
                      <li key={`${toDisplayText(item, "limitation")}-${index}`}>{toDisplayText(item, "Limitation unavailable.")}</li>
                    ))
                  )}
                </ul>
              </div>
            </>
          )}
        </div>
      )}
    </section>
  );
}
