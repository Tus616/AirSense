import { useEffect, useRef, useState } from "react";
import { queryDecisionCopilot } from "../../services/decisionApi";
import { asArray, formatPercent, labelize, toDisplayText } from "./decisionUtils";

const DEFAULT_QUESTIONS = [
  "Why is AQI worsening?",
  "What is the dominant source?",
  "Which agency should act first?",
  "What should schools do today?",
  "How reliable is this forecast?",
];

function buildInitialQuestions(suggestedQuestions) {
  const backendQuestions = asArray(suggestedQuestions)
    .map((item) => toDisplayText(item?.question, ""))
    .filter(Boolean);
  return backendQuestions.length > 0 ? backendQuestions.slice(0, 5) : DEFAULT_QUESTIONS;
}

export default function DecisionCopilotPanel({
  cityId = "",
  city,
  timelineFrame,
  degradedMode = false,
  suggestedQuestions,
  snapshotId,
}) {
  const [open, setOpen] = useState(false);
  const [question, setQuestion] = useState("");
  const [response, setResponse] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [requestSeed, setRequestSeed] = useState(0);
  const conversationIdRef = useRef(`copilot-${Date.now()}`);
  const latestQuestionRef = useRef("");
  const requestRef = useRef(null);

  useEffect(() => {
    if (requestRef.current) requestRef.current.abort();
    setResponse(null);
    setError("");
    setQuestion("");
  }, [cityId, city, timelineFrame, snapshotId]);

  const questionOptions = buildInitialQuestions(response?.suggestedQuestions || suggestedQuestions);
  const citations = asArray(response?.citations);
  const evidence = asArray(response?.evidence);
  const limitations = asArray(response?.limitations);

  async function submitQuestion(nextQuestion) {
    const trimmed = String(nextQuestion || "").replace(/\s+/g, " ").trim().slice(0, 500);
    if (!trimmed) return;

    if (requestRef.current) requestRef.current.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    latestQuestionRef.current = trimmed;
    setLoading(true);
    setError("");
    try {
      const result = await queryDecisionCopilot({
        cityId,
        city,
        question: trimmed,
        timelineFrame,
        conversationId: conversationIdRef.current,
        snapshotId,
        signal: controller.signal,
      });
      setResponse(result && typeof result === "object" ? result : null);
    } catch (err) {
      if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
      setResponse(null);
      setError(err?.response?.data?.message || err?.message || "Copilot request failed.");
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null;
        setLoading(false);
      }
    }
  }

  return (
    <>
      <button
        className={`decision-copilot-trigger ${open ? "is-open" : ""}`}
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-controls="decision-copilot-panel"
      >
        <span>Decision Copilot</span>
        <small>{response ? copilotModeLabel(response) : degradedMode ? "Grounded, partial-aware" : "Grounded answers"}</small>
      </button>

      {open && (
        <aside className="decision-copilot-panel" id="decision-copilot-panel" aria-label="Decision copilot panel">
          <div className="decision-panel__header">
            <div>
              <span className="decision-eyebrow">Decision Copilot</span>
              <h2>Evidence-Backed Answers</h2>
            </div>
            <button className="decision-button decision-button--compact" type="button" onClick={() => setOpen(false)}>
              Close
            </button>
          </div>

          <p className="decision-panel__note">
            Uses only decision, explainability, forecast, attribution, geospatial, enforcement, advisory, and timeline outputs.
          </p>

          {(degradedMode || response?.degradedMode) && (
            <div className="decision-inline-error">
              <span>Degraded mode is active. Answers may reflect forecast fallback or partial engine availability.</span>
            </div>
          )}

          <div className="decision-copilot-input">
            <textarea
              value={question}
              maxLength={500}
              placeholder="Ask why AQI is worsening, which source dominates, what officials should do, or how confidence changes over time."
              onChange={(event) => setQuestion(event.target.value)}
            />
            <div className="decision-copilot-actions">
              <span>{question.trim().length}/500</span>
              <button
                className="decision-button"
                type="button"
                disabled={loading || !question.trim()}
                onClick={() => submitQuestion(question)}
              >
                {loading ? "Thinking" : "Ask"}
              </button>
            </div>
          </div>

          <div className="decision-copilot-suggestions">
            {questionOptions.map((item) => (
              <button
                key={item}
                className="decision-copilot-suggestion"
                type="button"
                disabled={loading}
                onClick={() => {
                  setQuestion(item);
                  submitQuestion(item);
                }}
              >
                {item}
              </button>
            ))}
          </div>

          {loading && <p className="decision-panel__note">Selecting grounded evidence and building a concise answer.</p>}

          {error && (
            <div className="decision-inline-error">
              <span>{error}</span>
              <button
                className="decision-button decision-button--compact"
                type="button"
                onClick={() => {
                  setError("");
                  setRequestSeed((value) => value + 1);
                  submitQuestion(latestQuestionRef.current);
                }}
              >
                Retry
              </button>
            </div>
          )}

          {!loading && !error && !response && (
            <div className="decision-empty-line">
              Ask a question or pick a suggested prompt to inspect grounded decision intelligence for {city?.displayName || cityId || "the selected place"}.
            </div>
          )}

          {!loading && !error && response && (
            <div className="decision-copilot-response" data-request-seed={requestSeed}>
              <div className="decision-copilot-response__badges">
                <span className={`decision-chip ${copilotModeClass(response)}`}>{copilotModeLabel(response)}</span>
                {copilotStatus(response) === "PARTIAL" && <span className="decision-chip decision-chip--warn">Partial</span>}
                {copilotStatus(response) === "UNAVAILABLE" && <span className="decision-chip decision-chip--warn">Unavailable</span>}
              </div>
              <div className="decision-copilot-response__top">
                <div>
                  <span className="decision-eyebrow">Intent</span>
                  <strong>{labelize(response.intent)}</strong>
                </div>
                <div>
                  <span className="decision-eyebrow">Confidence</span>
                  <strong>{formatPercent(response.confidence)}</strong>
                </div>
              </div>

              <div className="decision-explainability__summary">
                <strong>{toDisplayText(response.answer, "No grounded answer returned.")}</strong>
                <span>{timelineFrame ? `Frame: ${timelineFrame}` : `Place: ${city?.displayName || cityId || "Selected place"}`}</span>
              </div>

              <div>
                <h3>Citations</h3>
                <div className="decision-copilot-citations">
                  {citations.length === 0 ? (
                    <span className="decision-chip">No citations returned</span>
                  ) : (
                    citations.map((citation, index) => (
                      <div className="decision-copilot-citation" key={`${citation.label || "citation"}-${index}`}>
                        <strong>{toDisplayText(citation.label, "Citation")}</strong>
                        <span>{toDisplayText(citation.value, "Unavailable")}</span>
                        <small>{labelize(citation.sourceType)} · {formatPercent(citation.confidence)}</small>
                      </div>
                    ))
                  )}
                </div>
              </div>

              <div>
                <h3>Evidence</h3>
                <ul className="decision-evidence-list">
                  {evidence.length === 0 ? (
                    <li>No evidence objects returned.</li>
                  ) : (
                    evidence.slice(0, 6).map((item, index) => (
                      <li key={`${item.signal || "evidence"}-${index}`}>
                        {toDisplayText(item.signal, "Signal")}: {toDisplayText(item.value, "Unavailable")} ({formatPercent(item.confidence)})
                      </li>
                    ))
                  )}
                </ul>
              </div>

              <div>
                <h3>Limitations</h3>
                <ul className="decision-evidence-list">
                  {limitations.length === 0 ? (
                    <li>No limitations reported.</li>
                  ) : (
                    limitations.map((item, index) => (
                      <li key={`${item}-${index}`}>{toDisplayText(item, "Limitation unavailable.")}</li>
                    ))
                  )}
                </ul>
              </div>
            </div>
          )}
        </aside>
      )}
    </>
  );
}

function copilotStatus(response) {
  const status = String(response?.status || "").trim().toUpperCase();
  if (["SUCCESS", "PARTIAL", "UNAVAILABLE"].includes(status)) return status;
  if (!response || !response.answer) return "UNAVAILABLE";
  if (response.degradedMode || asArray(response.limitations).length > 0) return "PARTIAL";
  return "SUCCESS";
}

function copilotModeLabel(response) {
  return String(response?.mode || "").trim().toUpperCase() === "GEMINI" ? "Gemini" : "Grounded fallback";
}

function copilotModeClass(response) {
  return String(response?.mode || "").trim().toUpperCase() === "GEMINI" ? "decision-chip--ok" : "";
}
