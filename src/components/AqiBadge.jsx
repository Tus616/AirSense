import { getAqiColor, getAqiCategory, getAqiTextColor } from "../utils/aqiUtils";

/**
 * Color-coded AQI badge/pill component
 * @param {{ aqi: number, size?: "sm"|"md"|"lg" }} props
 */
export default function AqiBadge({ aqi, size = "md" }) {
  const color = getAqiColor(aqi);
  const textColor = getAqiTextColor(aqi);
  const category = getAqiCategory(aqi);

  const sizeClasses = {
    sm: "aqi-badge--sm",
    md: "aqi-badge--md",
    lg: "aqi-badge--lg",
  };

  return (
    <span
      className={`aqi-badge ${sizeClasses[size]}`}
      style={{ backgroundColor: color, color: textColor }}
      title={`AQI ${aqi} — ${category}`}
    >
      <span className="aqi-badge__value">{aqi}</span>
      <span className="aqi-badge__label">{category}</span>
    </span>
  );
}
