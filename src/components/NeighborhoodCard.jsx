import AqiBadge from "./AqiBadge";
import { getAqiColor } from "../utils/aqiUtils";

/**
 * Neighborhood AQI card for citizen dashboard
 * @param {{ neighborhood: { id, name, aqi, category, dominantPollutant } }} props
 */
export default function NeighborhoodCard({ neighborhood }) {
  const borderColor = getAqiColor(neighborhood.aqi);

  return (
    <div className="neighborhood-card glass-card" style={{ borderLeftColor: borderColor }}>
      <div className="neighborhood-card__header">
        <h3 className="neighborhood-card__name">{neighborhood.name}</h3>
        <AqiBadge aqi={neighborhood.aqi} size="sm" />
      </div>
      <div className="neighborhood-card__info">
        <span className="neighborhood-card__pollutant">
          <span className="label">Pollutant</span>
          <span className="value">{neighborhood.dominantPollutant}</span>
        </span>
        <span className="neighborhood-card__category">
          <span className="label">Status</span>
          <span className="value" style={{ color: borderColor }}>{neighborhood.category}</span>
        </span>
      </div>
    </div>
  );
}
