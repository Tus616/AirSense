import AqiBadge from "./AqiBadge";
import { getAqiColor } from "../utils/aqiUtils";

/**
 * City-wide AQI summary hero card
 * @param {{ summary: { city, aqi, category, dominantPollutant, updatedAt } }} props
 */
export default function AqiSummaryCard({ summary }) {
  const borderColor = getAqiColor(summary.aqi);
  const updatedTime = new Date(summary.updatedAt).toLocaleTimeString("en-IN", {
    hour: "2-digit",
    minute: "2-digit",
  });

  return (
    <div className="aqi-summary-card glass-card" style={{ borderTopColor: borderColor }}>
      <div className="aqi-summary-card__header">
        <div className="aqi-summary-card__city">
          <span className="aqi-summary-card__icon">🏙️</span>
          <h2>{summary.city}</h2>
        </div>
        <AqiBadge aqi={summary.aqi} size="lg" />
      </div>
      <div className="aqi-summary-card__details">
        <div className="aqi-summary-card__detail">
          <span className="label">Dominant Pollutant</span>
          <span className="value">{summary.dominantPollutant}</span>
        </div>
        <div className="aqi-summary-card__detail">
          <span className="label">Category</span>
          <span className="value">{summary.category}</span>
        </div>
        <div className="aqi-summary-card__detail">
          <span className="label">Last Updated</span>
          <span className="value">{updatedTime}</span>
        </div>
      </div>
      <div className="aqi-summary-card__bar">
        <div
          className="aqi-summary-card__bar-fill"
          style={{
            width: `${Math.min((summary.aqi / 500) * 100, 100)}%`,
            backgroundColor: borderColor,
          }}
        />
      </div>
      <div className="aqi-summary-card__scale">
        <span>0</span><span>Good</span><span>Moderate</span><span>Poor</span><span>Severe</span><span>500</span>
      </div>
    </div>
  );
}
