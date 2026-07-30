import { asArray, formatScore, labelize, toDisplayText } from "./decisionUtils";
import { motion } from "../motion/MotionPrimitives";

function formatFrameTime(value) {
  if (!value) return "Pending";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Pending";
  return date.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

export default function TimelineSlider({ frames, selectedIndex, onChange, loading, error }) {
  const safeFrames = asArray(frames);
  const safeSelectedIndex = Math.min(Math.max(Number(selectedIndex) || 0, 0), Math.max(safeFrames.length - 1, 0));
  const activeFrame = safeFrames[safeSelectedIndex] || safeFrames[0];
  const unavailable = activeFrame?.unavailable === true;

  return (
    <section className="decision-panel decision-timeline-panel">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Temporal Intelligence</span>
          <h2>Replay Environmental Conditions</h2>
        </div>
        <span className="decision-chip">{loading ? "Loading" : `${safeFrames.length || 0} Frames`}</span>
      </div>

      {error && <div className="decision-empty-line">{toDisplayText(error, "Timeline unavailable.")}</div>}

      {safeFrames.length === 0 ? (
        <div className="decision-empty-line">
          {loading ? "Loading cached timeline frames." : "Historical replay is not configured for this station."}
        </div>
      ) : (
        <>
          <input
            className="decision-timeline-slider"
            type="range"
            min="0"
            max={safeFrames.length - 1}
            value={safeSelectedIndex}
            onChange={(event) => onChange(Number(event.target.value))}
          />
          <div className="decision-timeline-labels">
            {safeFrames.map((frame, index) => (
              <button
                key={frame.frameId || frame.label}
                className={index === safeSelectedIndex ? "is-active" : ""}
                type="button"
                onClick={() => onChange(index)}
              >
                {index === safeSelectedIndex && <motion.i layoutId="timeline-active-frame" transition={{ type: "spring", stiffness: 420, damping: 34 }} />}
                {toDisplayText(frame.label, `Frame ${index + 1}`)}
              </button>
            ))}
          </div>
          {unavailable && (
            <div className="decision-empty-line">
              {toDisplayText(activeFrame?.message, "Historical frame unavailable")}
            </div>
          )}
          <motion.div
            className="decision-timeline-summary"
            key={activeFrame?.frameId || activeFrame?.label || safeSelectedIndex}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.22 }}
          >
            <div>
              <span>AQI</span>
              <strong>{formatScore(activeFrame?.aqi)}</strong>
            </div>
            <div>
              <span>Risk</span>
              <strong>{labelize(activeFrame?.risk?.overallRiskLevel)}</strong>
            </div>
            <div>
              <span>Source</span>
              <strong>{labelize(activeFrame?.dominantSource)}</strong>
            </div>
            <div>
              <span>Time</span>
              <strong>{formatFrameTime(activeFrame?.timestamp)}</strong>
            </div>
          </motion.div>
        </>
      )}
    </section>
  );
}
