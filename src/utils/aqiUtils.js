// ============================================================================
// AQI Utility Helpers
// Maps AQI values → colors, labels, categories per Indian NAQI standards
// ============================================================================

/**
 * AQI breakpoints and their associated metadata (Indian NAQI standard)
 */
const AQI_BREAKPOINTS = [
  { min: 0,   max: 50,  category: "Good",           color: "#55a84f", textColor: "#ffffff", emoji: "🟢" },
  { min: 51,  max: 100, category: "Satisfactory",    color: "#a3c853", textColor: "#1a1a1a", emoji: "🟡" },
  { min: 101, max: 200, category: "Moderate",         color: "#fff833", textColor: "#1a1a1a", emoji: "🟠" },
  { min: 201, max: 300, category: "Poor",              color: "#f29c33", textColor: "#ffffff", emoji: "🔴" },
  { min: 301, max: 400, category: "Very Poor",         color: "#e93f33", textColor: "#ffffff", emoji: "🟣" },
  { min: 401, max: 500, category: "Severe",            color: "#af2d24", textColor: "#ffffff", emoji: "⛔" },
];

/**
 * Get the AQI breakpoint object for a given AQI value
 * @param {number} aqi
 * @returns {Object} breakpoint — { min, max, category, color, textColor, emoji }
 */
export function getAqiBreakpoint(aqi) {
  const clamped = Math.max(0, Math.min(500, aqi));
  return AQI_BREAKPOINTS.find((bp) => clamped >= bp.min && clamped <= bp.max) || AQI_BREAKPOINTS[5];
}

/**
 * Get color hex for an AQI value
 * @param {number} aqi
 * @returns {string} hex color
 */
export function getAqiColor(aqi) {
  return getAqiBreakpoint(aqi).color;
}

/**
 * Get category label for an AQI value
 * @param {number} aqi
 * @returns {string} category name
 */
export function getAqiCategory(aqi) {
  return getAqiBreakpoint(aqi).category;
}

/**
 * Get text color (for contrast) for an AQI value's badge
 * @param {number} aqi
 * @returns {string} hex color
 */
export function getAqiTextColor(aqi) {
  return getAqiBreakpoint(aqi).textColor;
}

/**
 * Format a date string to a short readable form
 * @param {string} dateStr — ISO date string
 * @returns {string} e.g. "Jul 7"
 */
export function formatDate(dateStr) {
  const date = new Date(dateStr);
  return date.toLocaleDateString("en-IN", { month: "short", day: "numeric" });
}

/**
 * Format a date string to a longer readable form
 * @param {string} dateStr — ISO date string
 * @returns {string} e.g. "Monday, Jul 7"
 */
export function formatDateLong(dateStr) {
  const date = new Date(dateStr);
  return date.toLocaleDateString("en-IN", { weekday: "long", month: "short", day: "numeric" });
}

/**
 * Compliance status badge color mapping
 */
export function getComplianceColor(status) {
  switch (status) {
    case "compliant":     return "#55a84f";
    case "non-compliant": return "#e93f33";
    case "under-review":  return "#f29c33";
    default:              return "#94a3b8";
  }
}

/**
 * Emission level badge color mapping
 */
export function getEmissionColor(level) {
  switch (level) {
    case "low":      return "#55a84f";
    case "medium":   return "#f29c33";
    case "high":     return "#e93f33";
    case "critical": return "#af2d24";
    default:         return "#94a3b8";
  }
}
