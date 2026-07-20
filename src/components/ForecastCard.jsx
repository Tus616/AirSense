import AqiBadge from "./AqiBadge";
import { getAqiColor, formatDateLong } from "../utils/aqiUtils";

/**
 * Single-day forecast card
 * @param {{ forecast: { date, aqi, category, high, low, advisory } }} props
 */
export default function ForecastCard({ forecast }) {
  const color = getAqiColor(forecast.aqi);

  return (
    <div className="forecast-card glass-card" style={{ borderTopColor: color }}>
      <div className="forecast-card__date">{formatDateLong(forecast.date)}</div>
      <div className="forecast-card__aqi-row">
        <AqiBadge aqi={forecast.aqi} size="md" />
        <div className="forecast-card__range">
          <span className="forecast-card__high">↑ {forecast.high}</span>
          <span className="forecast-card__low">↓ {forecast.low}</span>
        </div>
      </div>
      <p className="forecast-card__advisory">{forecast.advisory}</p>
    </div>
  );
}
