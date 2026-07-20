// ============================================================================
// MOCK DATA — Phase 1 (Standalone Frontend)
// All shapes are documented and designed to match future API contracts.
// City context: Delhi NCR, India
// ============================================================================

/**
 * @typedef {Object} CityAqiSummary
 * @property {string}  city              — City name
 * @property {number}  aqi               — Current composite AQI (0-500)
 * @property {string}  category          — AQI category label
 * @property {string}  dominantPollutant — Primary pollutant driving the AQI
 * @property {string}  updatedAt         — ISO-8601 timestamp of last update
 */
export const CITY_AQI_SUMMARY = {
  city: "Delhi NCR",
  aqi: 187,
  category: "Moderate",
  dominantPollutant: "PM2.5",
  updatedAt: "2026-07-07T13:30:00+05:30",
};

/**
 * @typedef {Object} SensorStation
 * @property {string}  id          — Unique station identifier
 * @property {string}  name        — Human-readable station name
 * @property {number}  lat         — Latitude
 * @property {number}  lng         — Longitude
 * @property {number}  aqi         — Current AQI reading
 * @property {string}  status      — "online" | "offline" | "maintenance"
 * @property {Object}  pollutants  — Key pollutant concentrations (µg/m³)
 */
export const SENSOR_STATIONS = [
  { id: "CPCB-DL01", name: "ITO",                lat: 28.6289, lng: 77.2415, aqi: 203, status: "online",      pollutants: { pm25: 124, pm10: 198, no2: 67, so2: 18, co: 1.8, o3: 42 } },
  { id: "CPCB-DL02", name: "Anand Vihar",        lat: 28.6469, lng: 77.3164, aqi: 267, status: "online",      pollutants: { pm25: 178, pm10: 245, no2: 82, so2: 24, co: 2.3, o3: 38 } },
  { id: "CPCB-DL03", name: "RK Puram",           lat: 28.5633, lng: 77.1726, aqi: 178, status: "online",      pollutants: { pm25: 108, pm10: 175, no2: 56, so2: 15, co: 1.5, o3: 48 } },
  { id: "CPCB-DL04", name: "Dwarka Sector 8",    lat: 28.5762, lng: 77.0714, aqi: 142, status: "online",      pollutants: { pm25: 82, pm10: 148, no2: 44, so2: 12, co: 1.2, o3: 52 } },
  { id: "CPCB-DL05", name: "Rohini",             lat: 28.7329, lng: 77.1181, aqi: 198, status: "online",      pollutants: { pm25: 118, pm10: 192, no2: 62, so2: 20, co: 1.7, o3: 44 } },
  { id: "CPCB-DL06", name: "Punjabi Bagh",       lat: 28.6683, lng: 77.1167, aqi: 210, status: "maintenance", pollutants: { pm25: 132, pm10: 205, no2: 70, so2: 22, co: 1.9, o3: 40 } },
  { id: "CPCB-DL07", name: "Mundka",             lat: 28.6817, lng: 77.0322, aqi: 235, status: "online",      pollutants: { pm25: 156, pm10: 228, no2: 76, so2: 26, co: 2.1, o3: 36 } },
  { id: "CPCB-DL08", name: "Nehru Nagar",        lat: 28.5682, lng: 77.2508, aqi: 165, status: "online",      pollutants: { pm25: 98, pm10: 162, no2: 52, so2: 14, co: 1.4, o3: 46 } },
  { id: "CPCB-DL09", name: "Sirifort",           lat: 28.5504, lng: 77.2159, aqi: 155, status: "offline",     pollutants: { pm25: 92, pm10: 155, no2: 48, so2: 13, co: 1.3, o3: 50 } },
  { id: "CPCB-DL10", name: "Narela",             lat: 28.8529, lng: 77.0929, aqi: 245, status: "online",      pollutants: { pm25: 164, pm10: 238, no2: 78, so2: 28, co: 2.2, o3: 34 } },
];

/**
 * @typedef {Object} Neighborhood
 * @property {string}  id                — Unique identifier
 * @property {string}  name              — Neighborhood / locality name
 * @property {number}  aqi               — Current AQI
 * @property {string}  category          — AQI category label
 * @property {string}  dominantPollutant — Primary pollutant
 */
export const NEIGHBORHOODS = [
  { id: "nb-01", name: "Connaught Place",   aqi: 168, category: "Moderate",  dominantPollutant: "PM2.5" },
  { id: "nb-02", name: "Karol Bagh",        aqi: 195, category: "Moderate",  dominantPollutant: "PM10"  },
  { id: "nb-03", name: "Saket",             aqi: 142, category: "Moderate",  dominantPollutant: "PM2.5" },
  { id: "nb-04", name: "Lajpat Nagar",      aqi: 178, category: "Moderate",  dominantPollutant: "NO₂"   },
  { id: "nb-05", name: "Chandni Chowk",     aqi: 224, category: "Poor",      dominantPollutant: "PM2.5" },
  { id: "nb-06", name: "Vasant Kunj",       aqi: 128, category: "Moderate",  dominantPollutant: "PM10"  },
  { id: "nb-07", name: "Mayur Vihar",       aqi: 208, category: "Poor",      dominantPollutant: "PM2.5" },
  { id: "nb-08", name: "Janakpuri",         aqi: 156, category: "Moderate",  dominantPollutant: "O₃"    },
  { id: "nb-09", name: "Dwarka",            aqi: 134, category: "Moderate",  dominantPollutant: "PM10"  },
  { id: "nb-10", name: "Greater Kailash",   aqi: 162, category: "Moderate",  dominantPollutant: "PM2.5" },
  { id: "nb-11", name: "Pitampura",         aqi: 188, category: "Moderate",  dominantPollutant: "PM10"  },
  { id: "nb-12", name: "Nehru Place",       aqi: 172, category: "Moderate",  dominantPollutant: "NO₂"   },
];

/**
 * @typedef {Object} HistoricalTrendPoint
 * @property {string}  date  — ISO date string (YYYY-MM-DD)
 * @property {number}  aqi   — Daily average AQI
 * @property {number}  pm25  — PM2.5 µg/m³
 * @property {number}  pm10  — PM10  µg/m³
 * @property {number}  no2   — NO₂   µg/m³
 * @property {number}  o3    — O₃    µg/m³
 */
export const HISTORICAL_TRENDS = [
  { date: "2026-06-07", aqi: 145, pm25:  82, pm10: 140, no2: 42, o3: 55 },
  { date: "2026-06-08", aqi: 158, pm25:  94, pm10: 155, no2: 48, o3: 50 },
  { date: "2026-06-09", aqi: 132, pm25:  76, pm10: 128, no2: 38, o3: 58 },
  { date: "2026-06-10", aqi: 168, pm25: 102, pm10: 165, no2: 55, o3: 45 },
  { date: "2026-06-11", aqi: 175, pm25: 108, pm10: 172, no2: 58, o3: 42 },
  { date: "2026-06-12", aqi: 192, pm25: 118, pm10: 188, no2: 64, o3: 38 },
  { date: "2026-06-13", aqi: 210, pm25: 134, pm10: 205, no2: 72, o3: 35 },
  { date: "2026-06-14", aqi: 198, pm25: 122, pm10: 195, no2: 66, o3: 40 },
  { date: "2026-06-15", aqi: 178, pm25: 108, pm10: 175, no2: 56, o3: 46 },
  { date: "2026-06-16", aqi: 162, pm25:  96, pm10: 158, no2: 50, o3: 52 },
  { date: "2026-06-17", aqi: 148, pm25:  86, pm10: 142, no2: 44, o3: 56 },
  { date: "2026-06-18", aqi: 155, pm25:  92, pm10: 150, no2: 48, o3: 52 },
  { date: "2026-06-19", aqi: 172, pm25: 105, pm10: 168, no2: 55, o3: 44 },
  { date: "2026-06-20", aqi: 188, pm25: 115, pm10: 182, no2: 62, o3: 40 },
  { date: "2026-06-21", aqi: 205, pm25: 130, pm10: 200, no2: 68, o3: 36 },
  { date: "2026-06-22", aqi: 195, pm25: 120, pm10: 190, no2: 64, o3: 42 },
  { date: "2026-06-23", aqi: 182, pm25: 112, pm10: 178, no2: 58, o3: 45 },
  { date: "2026-06-24", aqi: 170, pm25: 104, pm10: 166, no2: 54, o3: 48 },
  { date: "2026-06-25", aqi: 158, pm25:  94, pm10: 155, no2: 48, o3: 52 },
  { date: "2026-06-26", aqi: 165, pm25:  98, pm10: 162, no2: 52, o3: 50 },
  { date: "2026-06-27", aqi: 178, pm25: 108, pm10: 175, no2: 56, o3: 46 },
  { date: "2026-06-28", aqi: 192, pm25: 118, pm10: 188, no2: 64, o3: 38 },
  { date: "2026-06-29", aqi: 215, pm25: 138, pm10: 210, no2: 74, o3: 34 },
  { date: "2026-06-30", aqi: 228, pm25: 148, pm10: 222, no2: 78, o3: 32 },
  { date: "2026-07-01", aqi: 202, pm25: 126, pm10: 198, no2: 68, o3: 38 },
  { date: "2026-07-02", aqi: 188, pm25: 115, pm10: 184, no2: 62, o3: 42 },
  { date: "2026-07-03", aqi: 175, pm25: 106, pm10: 172, no2: 56, o3: 46 },
  { date: "2026-07-04", aqi: 168, pm25: 100, pm10: 165, no2: 52, o3: 48 },
  { date: "2026-07-05", aqi: 182, pm25: 112, pm10: 178, no2: 58, o3: 44 },
  { date: "2026-07-06", aqi: 195, pm25: 120, pm10: 190, no2: 64, o3: 40 },
];

/**
 * @typedef {Object} SensorReliabilityMetric
 * @property {string}  stationId        — Matching SensorStation.id
 * @property {string}  name             — Station name
 * @property {number}  uptime           — Uptime % (0-100)
 * @property {number}  dataCompleteness — Data completeness % (0-100)
 * @property {string}  lastCalibration  — ISO date of last calibration
 */
export const SENSOR_RELIABILITY = [
  { stationId: "CPCB-DL01", name: "ITO",             uptime: 98.2, dataCompleteness: 96.5, lastCalibration: "2026-06-15" },
  { stationId: "CPCB-DL02", name: "Anand Vihar",     uptime: 97.5, dataCompleteness: 95.8, lastCalibration: "2026-06-10" },
  { stationId: "CPCB-DL03", name: "RK Puram",        uptime: 99.1, dataCompleteness: 98.2, lastCalibration: "2026-06-20" },
  { stationId: "CPCB-DL04", name: "Dwarka Sec. 8",   uptime: 96.8, dataCompleteness: 94.3, lastCalibration: "2026-06-12" },
  { stationId: "CPCB-DL05", name: "Rohini",          uptime: 95.4, dataCompleteness: 93.1, lastCalibration: "2026-06-08" },
  { stationId: "CPCB-DL06", name: "Punjabi Bagh",    uptime: 78.3, dataCompleteness: 72.6, lastCalibration: "2026-05-20" },
  { stationId: "CPCB-DL07", name: "Mundka",          uptime: 94.2, dataCompleteness: 91.8, lastCalibration: "2026-06-05" },
  { stationId: "CPCB-DL08", name: "Nehru Nagar",     uptime: 97.8, dataCompleteness: 96.1, lastCalibration: "2026-06-18" },
  { stationId: "CPCB-DL09", name: "Sirifort",        uptime: 42.5, dataCompleteness: 38.2, lastCalibration: "2026-04-15" },
  { stationId: "CPCB-DL10", name: "Narela",          uptime: 93.6, dataCompleteness: 90.4, lastCalibration: "2026-06-02" },
];

/**
 * @typedef {Object} SourceAttribution
 * @property {string}  source      — Pollution source category
 * @property {number}  percentage  — Contribution % (all should sum to 100)
 */
export const SOURCE_ATTRIBUTION = [
  { source: "Vehicular Emissions",  percentage: 34 },
  { source: "Industrial",           percentage: 22 },
  { source: "Construction Dust",    percentage: 16 },
  { source: "Biomass Burning",      percentage: 12 },
  { source: "Power Plants",         percentage: 8  },
  { source: "Waste Burning",        percentage: 5  },
  { source: "Other / Natural",      percentage: 3  },
];

/**
 * @typedef {Object} IndustrialPolluter
 * @property {string}  id                — Unique identifier
 * @property {string}  name              — Facility name
 * @property {number}  lat               — Latitude
 * @property {number}  lng               — Longitude
 * @property {string}  type              — Industry type
 * @property {string}  complianceStatus  — "compliant" | "non-compliant" | "under-review"
 * @property {string}  emissionLevel     — "low" | "medium" | "high" | "critical"
 */
export const INDUSTRIAL_POLLUTERS = [
  { id: "ip-01", name: "Bawana Industrial Area Unit-A",    lat: 28.7932, lng: 77.0515, type: "Manufacturing",     complianceStatus: "non-compliant",  emissionLevel: "critical" },
  { id: "ip-02", name: "Narela Metal Works",               lat: 28.8480, lng: 77.1020, type: "Metal Processing",  complianceStatus: "non-compliant",  emissionLevel: "high"     },
  { id: "ip-03", name: "Okhla Waste-to-Energy Plant",      lat: 28.5310, lng: 77.2710, type: "Waste Processing",  complianceStatus: "under-review",   emissionLevel: "high"     },
  { id: "ip-04", name: "Wazirpur Industrial Cluster",      lat: 28.6970, lng: 77.1630, type: "Manufacturing",     complianceStatus: "non-compliant",  emissionLevel: "critical" },
  { id: "ip-05", name: "Mundka Chemical Plant",            lat: 28.6850, lng: 77.0250, type: "Chemicals",         complianceStatus: "compliant",      emissionLevel: "medium"   },
  { id: "ip-06", name: "GT Karnal Road Dyeing Unit",       lat: 28.7420, lng: 77.1380, type: "Textiles",          complianceStatus: "under-review",   emissionLevel: "medium"   },
  { id: "ip-07", name: "Tikri Border Brick Kilns",         lat: 28.6650, lng: 76.9680, type: "Construction",      complianceStatus: "non-compliant",  emissionLevel: "high"     },
];

/**
 * AQI heatmap points — [lat, lng, intensity]
 * intensity range: 0.0 (low) to 1.0 (severe)
 */
export const HEATMAP_POINTS = [
  [28.6289, 77.2415, 0.65], [28.6469, 77.3164, 0.85], [28.5633, 77.1726, 0.55],
  [28.5762, 77.0714, 0.42], [28.7329, 77.1181, 0.62], [28.6683, 77.1167, 0.68],
  [28.6817, 77.0322, 0.75], [28.5682, 77.2508, 0.52], [28.5504, 77.2159, 0.48],
  [28.8529, 77.0929, 0.78], [28.6350, 77.2250, 0.58], [28.6100, 77.2300, 0.60],
  [28.6550, 77.2100, 0.54], [28.5900, 77.2500, 0.50], [28.7100, 77.2000, 0.64],
  [28.7400, 77.1100, 0.66], [28.6700, 77.0800, 0.70], [28.7200, 77.0500, 0.72],
  [28.6900, 77.1500, 0.68], [28.6600, 77.2800, 0.56], [28.6200, 77.3000, 0.82],
  [28.5800, 77.1900, 0.46], [28.5500, 77.1500, 0.40], [28.6000, 77.1200, 0.44],
  [28.6400, 77.1800, 0.58], [28.6800, 77.2200, 0.62], [28.7000, 77.2600, 0.60],
  [28.7600, 77.1200, 0.70], [28.7800, 77.0800, 0.74], [28.8000, 77.0600, 0.76],
  [28.8200, 77.1100, 0.72], [28.6150, 77.2600, 0.56], [28.5700, 77.2200, 0.48],
  [28.5400, 77.2400, 0.52], [28.6500, 77.0500, 0.68], [28.6300, 77.1000, 0.50],
  [28.7500, 77.0300, 0.78], [28.6050, 77.1700, 0.44], [28.5950, 77.0900, 0.42],
  [28.6750, 77.3100, 0.80], [28.6250, 77.2700, 0.54], [28.7150, 77.1600, 0.66],
  [28.6850, 77.2500, 0.58], [28.6450, 77.1400, 0.52], [28.7350, 77.0700, 0.72],
  [28.5850, 77.2800, 0.50], [28.7050, 77.0100, 0.74], [28.6650, 77.1900, 0.60],
  [28.6950, 77.0900, 0.70], [28.5550, 77.2000, 0.46],
];

/**
 * Predicted pollution plume — GeoJSON FeatureCollection
 * Represents the forecasted spread direction of PM2.5 concentration.
 */
export const PREDICTED_PLUME = {
  type: "FeatureCollection",
  features: [
    {
      type: "Feature",
      properties: { pollutant: "PM2.5", level: "high", forecast_hour: 24 },
      geometry: {
        type: "Polygon",
        coordinates: [[
          [77.05, 28.68], [77.12, 28.72], [77.18, 28.74],
          [77.25, 28.72], [77.28, 28.68], [77.26, 28.64],
          [77.20, 28.60], [77.14, 28.58], [77.08, 28.60],
          [77.04, 28.64], [77.05, 28.68],
        ]],
      },
    },
    {
      type: "Feature",
      properties: { pollutant: "PM2.5", level: "moderate", forecast_hour: 48 },
      geometry: {
        type: "Polygon",
        coordinates: [[
          [77.00, 28.70], [77.10, 28.76], [77.20, 28.78],
          [77.30, 28.76], [77.34, 28.70], [77.32, 28.62],
          [77.24, 28.56], [77.14, 28.54], [77.06, 28.56],
          [76.98, 28.62], [77.00, 28.70],
        ]],
      },
    },
  ],
};

/**
 * @typedef {Object} ForecastDay
 * @property {string}  date      — ISO date (YYYY-MM-DD)
 * @property {number}  aqi       — Predicted average AQI
 * @property {string}  category  — Predicted AQI category
 * @property {number}  high      — Predicted daily high AQI
 * @property {number}  low       — Predicted daily low AQI
 * @property {string}  advisory  — Short advisory text
 */
export const THREE_DAY_FORECAST = [
  {
    date: "2026-07-08",
    aqi: 198,
    category: "Moderate",
    high: 225,
    low: 162,
    advisory: "Air quality may cause discomfort for sensitive groups. Limit prolonged outdoor exertion.",
  },
  {
    date: "2026-07-09",
    aqi: 172,
    category: "Moderate",
    high: 198,
    low: 145,
    advisory: "Improving conditions expected. Moderate outdoor activity is acceptable for most.",
  },
  {
    date: "2026-07-10",
    aqi: 148,
    category: "Moderate",
    high: 170,
    low: 128,
    advisory: "Further improvement likely. Sensitive individuals should still monitor symptoms.",
  },
];

/**
 * @typedef {Object} HealthAdvisory
 * @property {string}   level            — "green" | "yellow" | "orange" | "red" | "maroon"
 * @property {string}   headline         — Short headline
 * @property {string}   body             — Detailed advisory text
 * @property {string[]} recommendations  — List of actionable recommendations
 * @property {Object}   vulnerable       — Extra guidance for vulnerable populations
 */
export const HEALTH_ADVISORY = {
  level: "orange",
  headline: "Moderate to Poor Air Quality — Take Precautions",
  body: "Current AQI levels in Delhi NCR are in the Moderate to Poor range. PM2.5 concentrations are elevated due to a combination of vehicular emissions, construction dust, and unfavourable meteorological conditions. The situation is expected to improve gradually over the next 48-72 hours as wind patterns shift.",
  recommendations: [
    "Reduce prolonged outdoor physical activity, especially during peak traffic hours (8-10 AM, 5-8 PM).",
    "Keep windows closed and use air purifiers indoors if available.",
    "Wear N95 masks if commuting on two-wheelers or walking in high-traffic areas.",
    "Stay hydrated and monitor for symptoms like coughing, throat irritation, or shortness of breath.",
    "Check real-time AQI updates before planning outdoor events.",
  ],
  vulnerable: {
    headline: "High Risk for Sensitive Groups",
    body: "Children under 15, adults over 65, pregnant women, and individuals with asthma, COPD, or cardiovascular conditions should take extra precautions.",
    recommendations: [
      "Avoid all outdoor exercise until AQI drops below 100.",
      "Keep rescue inhalers accessible at all times.",
      "Consider postponing non-essential outdoor travel.",
      "Consult your physician if symptoms worsen.",
    ],
  },
};

/**
 * Non-sensitive user profile stubs used for UI display and component testing.
 * Passwords are NOT stored here — authentication always goes through the backend API.
 *
 * @typedef {Object} MockUser
 * @property {string}  id    — Unique user ID
 * @property {string}  email — Login email (not used for auth — UI display only)
 * @property {string}  name  — Display name
 * @property {string}  role  — "ADMIN" | "CITIZEN"
 */
export const MOCK_USERS = [
  {
    id: "usr-admin-01",
    email: "admin@example.com",
    name: "Admin Official",
    role: "ADMIN",
  },
  {
    id: "usr-citizen-01",
    email: "citizen@example.com",
    name: "Citizen User",
    role: "CITIZEN",
  },
];
