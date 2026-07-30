const VALID_HORIZONS = ["24h", "48h", "72h"];

export function array(value) {
  return Array.isArray(value) ? value : [];
}

export function numberOrNull(value) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function percentText(value, fallback = "Evidence not available") {
  const parsed = numberOrNull(value);
  if (parsed === null) return fallback;
  return `${Math.round(parsed > 1 ? parsed : parsed * 100)}%`;
}

export function enumLabel(value, fallback = "Evidence not available") {
  if (value === null || value === undefined || value === "") return fallback;
  const key = String(value).trim().toUpperCase();
  const labels = {
    NO_ACTION_REQUIRED: "No immediate enforcement required",
    MONITORING: "Continue monitoring",
    OPEN_METEO_PROVIDER_FORECAST: "Atmospheric Forecast",
    CHRONOS_BOLT_ZERO_SHOT: "Pretrained AI Forecast",
    PERSISTENCE_FALLBACK: "Persistence fallback",
    PERSISTENCE: "Persistence fallback",
    DERIVED_FROM_REAL_DATA: "Derived from live evidence",
    OBSERVED_REAL_DATA: "Observed live data",
    RULE_BASED_INFERENCE: "Rule-based guidance",
    PARTIAL: "Limited evidence",
    INSUFFICIENT: "Limited evidence",
    UNKNOWN: "Unknown / insufficiently explained",
    CHRONOS_DISABLED: "Optional AI model not used",
    UNAVAILABLE: fallback,
    NOT_REQUIRED: "No agency escalation required",
    PENDING: "Agency assignment pending",
    ASSIGNED: "Agency assigned",
    LOW: "Low",
    MEDIUM: "Medium",
    HIGH: "High",
    CRITICAL: "Critical",
    MODERATE: "Moderate",
    NORMAL: "Moderate",
    ELEVATED: "Elevated",
    GOOD: "Good",
    STABLE_TO_IMPROVING: "Stable to improving",
    STABLE: "Stable",
    WORSENING: "Worsening",
    DISABLED_FOR_CURRENT_DEPLOYMENT: "Disabled for current deployment",
    ONLINE: "Online",
    SUCCESS: "Operational",
    AVAILABLE: "Available",
  };
  if (labels[key]) return labels[key];
  return String(value).replace(/_/g, " ").toLowerCase().replace(/\b\w/g, (char) => char.toUpperCase());
}

export function getNormalizedForecastPoints(forecastResult) {
  if (!forecastResult) return [];
  if (Array.isArray(forecastResult.forecasts) && forecastResult.forecasts.length > 0) {
    return forecastResult.forecasts.map((point) => ({
      key: `${point.horizonHours || ""}h`,
      ...point,
    }));
  }
  const forecast = forecastResult.forecast || {};
  return VALID_HORIZONS.map((key) => ({
    key,
    ...(forecast[key] || {}),
  }));
}

export function forecastPointAvailable(point) {
  const value = numberOrNull(point?.predictedAqi);
  return value !== null && value > 0;
}

export function normalizeForecast(forecastResult) {
  const points = getNormalizedForecastPoints(forecastResult);
  const validPoints = points.filter(forecastPointAvailable);
  const pointEngine = points.find((point) => point?.engine || point?.mode)?.engine
    || points.find((point) => point?.engine || point?.mode)?.mode
    || "";
  const engine = pointEngine || forecastResult?.engine || forecastResult?.mode || "";
  const engineKey = String(engine || "").toUpperCase();
  const providerForecast = engineKey === "OPEN_METEO_PROVIDER_FORECAST"
    || validPoints.some((point) => String(point?.engine || point?.mode || "").toUpperCase() === "OPEN_METEO_PROVIDER_FORECAST")
    || String(forecastResult?.engine || forecastResult?.mode || "").toUpperCase() === "OPEN_METEO_PROVIDER_FORECAST";
  const persistenceFallbackCount = validPoints.filter((point) => {
    const mode = String(point?.engine || point?.mode || "").toUpperCase();
    return mode === "PERSISTENCE" || mode === "PERSISTENCE_FALLBACK" || point?.fallbackUsed === true;
  }).length;
  return {
    points,
    validPoints,
    available: validPoints.length > 0,
    allExpectedHorizonsAvailable: VALID_HORIZONS.every((key) => points.some((point) => point.key === key && forecastPointAvailable(point))),
    horizonCount: validPoints.length,
    engine,
    engineLabel: providerForecast ? "Atmospheric Forecast" : enumLabel(engine, "Forecast unavailable"),
    provider: points.find((point) => point?.provider)?.provider || forecastResult?.provider || forecastResult?.currentProvider || "",
    providerLabel: enumLabel(points.find((point) => point?.provider)?.provider || forecastResult?.provider || forecastResult?.currentProvider || "", "Provider evidence not available"),
    standard: points.find((point) => point?.aqiStandard)?.aqiStandard || forecastResult?.forecastStandard || forecastResult?.aqiStandard || "",
    providerForecast,
    persistenceFallbackCount,
    promotedHorizonCount: validPoints.filter((point) => point?.modelPromotionStatus === "PROMOTED" || point?.promotionStatus === "PROMOTED").length,
    optionalModelNote: providerForecast ? "Optional Chronos model was skipped; Open-Meteo provider forecast was used." : "",
  };
}

export function normalizeOperationalHealth(decision, timeline, hasApiError = false) {
  const forecast = normalizeForecast(decision?.forecast);
  const frames = array(timeline?.frames);
  const providerDown = decision?.environmentalSignals?.aqiAvailable === false;
  const backendFailures = Object.keys(decision?.engineStatus?.failureReasons || {}).length;
  const degraded = Boolean(hasApiError || providerDown || backendFailures || (!forecast.available && !numberOrNull(decision?.currentAQI)));
  return {
    degraded,
    status: degraded ? "Attention needed" : "Operational",
    providerForecast: forecast.available && forecast.providerForecast ? "Online" : forecast.available ? "Available" : "Evidence not available",
    forecastHorizonCount: forecast.horizonCount,
    timelineFrameCount: frames.length,
    optionalAiModel: forecast.providerForecast ? "Disabled for current deployment" : enumLabel(decision?.engineStatus?.optionalAiModelStatus, "Not used"),
    promotedHorizonCount: decision?.engineStatus?.locallyPromotedModelHorizonCount ?? forecast.promotedHorizonCount,
    persistenceFallbackHorizonCount: decision?.engineStatus?.persistenceFallbackHorizonCount ?? forecast.persistenceFallbackCount,
  };
}

export function aqiRiskLabel(aqi) {
  const value = numberOrNull(aqi);
  if (value === null) return "Evidence not available";
  if (value <= 50) return "Good";
  if (value <= 100) return "Moderate";
  if (value <= 200) return "Elevated";
  if (value <= 300) return "High";
  if (value <= 400) return "Severe";
  return "Emergency";
}

export function normalizeRiskDecision(decision) {
  const forecast = normalizeForecast(decision?.forecast);
  const currentAqi = numberOrNull(decision?.currentAQI ?? decision?.environmentalSignals?.currentAqi ?? decision?.environmentalSignals?.aqi);
  const peakForecastAqi = forecast.validPoints.reduce((max, point) => Math.max(max, numberOrNull(point.predictedAqi) ?? 0), 0) || null;
  const peak = Math.max(currentAqi ?? 0, peakForecastAqi ?? currentAqi ?? 0);
  const riskLabel = enumLabel(decision?.riskAssessment?.riskLevel || decision?.riskAssessment?.overallRiskLevel, aqiRiskLabel(peak));
  const trend = peakForecastAqi == null ? "Evidence not available" : peakForecastAqi < (currentAqi ?? peakForecastAqi) ? "Stable to improving" : peakForecastAqi === currentAqi ? "Stable" : "Worsening";
  return {
    available: currentAqi !== null || peakForecastAqi !== null,
    riskLevel: riskLabel,
    currentAqi,
    peakForecastAqi,
    trend,
    decisionSummary: decision?.riskAssessment?.decisionSummary || decision?.summary?.whatShouldOfficialsDoNow || "Continue monitoring.",
  };
}

export function normalizeEnforcement(decision) {
  const enforcement = decision?.enforcement || {};
  const recommendations = array(enforcement.recommendations || enforcement.actions || enforcement.recommendedActions);
  const top = recommendations[0] || {};
  const noAgencyRequired = top.agencyStatus === "NOT_REQUIRED" || ["MONITORING", "NO_ACTION_REQUIRED"].includes(String(top.actionType || "").toUpperCase());
  const agencies = array(enforcement.agencies || enforcement.responsibleAgencies)
    .concat(array(recommendations.map((item) => item.responsibleAgency).filter(Boolean)));
  const confidence = top.confidence ?? enforcement.confidence ?? decision?.moduleStatuses?.enforcement?.confidence;
  return {
    recommendations,
    top,
    actionCount: recommendations.length,
    priority: enumLabel(top.priorityLevel || enforcement.priority || enforcement.severity, recommendations.length ? "Low" : "Evidence not available"),
    actionLabel: top.actionLabel || enumLabel(top.actionType, "No immediate enforcement required"),
    title: top.title || (["MONITORING", "NO_ACTION_REQUIRED"].includes(String(top.actionType || "").toUpperCase()) ? "Continue monitoring" : enumLabel(top.actionType, "Action queue")),
    confidenceText: percentText(confidence),
    confidence,
    agencies: noAgencyRequired ? [] : [...new Set(agencies)],
    agencyStatus: noAgencyRequired ? "No agency escalation required" : agencies.length > 0 ? `${agencies.length} agency${agencies.length === 1 ? "" : "ies"}` : "Agency assignment pending",
    actionWindow: top.actionWindow || "Next 24 hours",
    forecastAvailable: Boolean(top.forecastAvailable) || normalizeForecast(decision?.forecast).available,
    reason: top.reason || "Current and forecast evidence support routine monitoring.",
    recommendedActions: array(top.recommendedActions),
  };
}

export function normalizeAttribution(attribution) {
  const sources = array(attribution?.sources).map((source) => {
    const contribution = numberOrNull(source.estimatedContributionPercent ?? source.percentage ?? source.contributionPercent);
    return {
      ...source,
      contribution,
      contributionText: contribution === null ? "Evidence not available" : `${Math.round(contribution)}%`,
      sourceLabel: enumLabel(source.displayName || source.sourceType, "Unknown / insufficiently explained"),
      dataOriginLabel: enumLabel(source.dataOrigin || "DERIVED_FROM_REAL_DATA"),
      availabilityLabel: enumLabel(source.dataAvailability || "PARTIAL"),
      confidenceText: percentText(source.confidence),
    };
  });
  const namedSource = sources
    .filter((source) => {
      const type = String(source.sourceType || source.displayName || "").toUpperCase();
      return type !== "UNKNOWN" && type !== "SECONDARY_AEROSOL_OR_OTHER";
    })
    .sort((a, b) => (b.contribution ?? -1) - (a.contribution ?? -1))[0];
  const leadingKnown = namedSource || sources
    .filter((source) => String(source.sourceType || source.displayName || "").toUpperCase() !== "UNKNOWN")
    .sort((a, b) => (b.contribution ?? -1) - (a.contribution ?? -1))[0];
  const total = sources.reduce((sum, source) => sum + (source.contribution ?? 0), 0);
  return {
    sources,
    leadingSource: leadingKnown || sources[0] || null,
    totalContribution: Math.round(total),
    confidenceText: percentText(attribution?.overallConfidence),
    confidence: attribution?.overallConfidence,
  };
}
