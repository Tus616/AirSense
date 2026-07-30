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
    NOT_REQUIRED: "Air Quality Command Center",
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
    agencyStatus: noAgencyRequired ? "Air Quality Command Center" : agencies.length > 0 ? `${agencies.length} agency${agencies.length === 1 ? "" : "ies"}` : "Air Quality Command Center",
    actionWindow: top.actionWindow || "Next 24 hours",
    forecastAvailable: Boolean(top.forecastAvailable) || normalizeForecast(decision?.forecast).available,
    reason: top.reason || "Current and forecast evidence support routine monitoring.",
    recommendedActions: array(top.recommendedActions),
  };
}

function cleanSentence(value, fallback = "Evidence not available") {
  const text = firstText([value], fallback)
    .replace(/\s+/g, " ")
    .replace(/\s+([.,;:!?])/g, "$1")
    .replace(/([.!?])(?=\S)/g, "$1 ")
    .trim();
  return text || fallback;
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

function firstText(values, fallback = "Evidence not available") {
  const value = values.find((item) => item !== null && item !== undefined && item !== "");
  return value === undefined ? fallback : String(value);
}

function actionTitle(item, fallback = "Continue monitoring") {
  return firstText([
    item?.title,
    item?.actionLabel,
    item?.name,
    enumLabel(item?.actionType || item?.action, ""),
  ], fallback);
}

function actionReason(item, fallback = "Current evidence supports routine monitoring.") {
  return cleanSentence(firstText([
    item?.reason,
    item?.description,
    item?.message,
    item?.summary,
    array(item?.recommendedActions).join("; "),
  ], fallback), fallback);
}

function featureCollection(layer) {
  return array(layer?.geoJson?.features).length > 0 ? array(layer.geoJson.features) : array(layer?.features);
}

function decisionLayers(decision) {
  return array(decision?.geospatial?.layers)
    .concat(array(decision?.geoSpatial?.layers))
    .concat(array(decision?.geoSpatialIntelligence?.layers));
}

function priorityScore(level, aqi) {
  const risk = String(level || "").toUpperCase();
  if (["EMERGENCY", "SEVERE", "CRITICAL", "HAZARDOUS"].includes(risk)) return 5;
  if (["HIGH", "VERY_POOR", "VERY HIGH", "UNHEALTHY"].includes(risk)) return 4;
  if (["ELEVATED", "MODERATE", "POOR"].includes(risk)) return 3;
  const value = numberOrNull(aqi);
  if (value === null) return 1;
  if (value > 300) return 5;
  if (value > 200) return 4;
  if (value > 100) return 3;
  if (value > 50) return 2;
  return 1;
}

function priorityLabel(score) {
  if (score >= 5) return "Critical";
  if (score >= 4) return "High";
  if (score >= 3) return "Medium";
  return "Low";
}

function responsibleUnit(value, fallback = "Air Quality Command Center") {
  const text = firstText([value], fallback);
  const key = text.toUpperCase();
  if (!text || key === "NOT_REQUIRED" || key.includes("NO AGENCY ESCALATION")) return fallback;
  if (key.includes("TRAFFIC")) return "Traffic Police";
  if (key.includes("POLLUTION") || key.includes("INDUSTRIAL")) return "Pollution Control Board";
  if (key.includes("HEALTH") || key.includes("HOSPITAL")) return "Public Health Department";
  if (key.includes("MUNICIPAL")) return "Municipal Corporation";
  return text;
}

function unknownContribution(attribution) {
  return normalizeAttribution(attribution).sources.find((source) => {
    const key = String(source.sourceType || source.displayName || "").toUpperCase();
    return key === "UNKNOWN" || key.includes("UNKNOWN");
  });
}

function sensitiveLocationSummary(props = {}, decision = {}) {
  const schoolCount = numberOrNull(props.schoolCount ?? props.schoolsCount ?? decision?.geospatial?.summary?.schoolCount);
  const hospitalCount = numberOrNull(props.hospitalCount ?? props.hospitalsCount ?? decision?.geospatial?.summary?.hospitalCount);
  const groups = array(props.vulnerableGroups || decision?.advisories?.vulnerableGroups || decision?.advisories?.sensitiveGroups)
    .map((item) => firstText([item?.label, item?.name, item], ""))
    .filter(Boolean);
  const parts = [];
  if (schoolCount !== null) parts.push(`${schoolCount} school${schoolCount === 1 ? "" : "s"}`);
  if (hospitalCount !== null) parts.push(`${hospitalCount} hospital${hospitalCount === 1 ? "" : "s"}`);
  if (groups.length > 0) parts.push(`${groups.slice(0, 3).join(", ")} groups`);
  return parts.length > 0
    ? parts.join(", ")
    : "Sensitive locations and vulnerable groups are shown; population exposure estimation is not yet connected.";
}

export function normalizeAreaIntelligence(decision) {
  const forecast = normalizeForecast(decision?.forecast);
  const risk = normalizeRiskDecision(decision);
  const attribution = normalizeAttribution(decision?.attribution);
  const enforcement = normalizeEnforcement(decision);
  const hotspotRows = decisionLayers(decision)
    .filter((layer) => String(layer?.layerType || layer?.layerId || "").includes("HOTSPOT"))
    .flatMap((layer) => featureCollection(layer).map((feature, index) => {
      const props = feature?.properties || {};
      const aqi = numberOrNull(props.aqi ?? props.currentAqi ?? props.forecastAqi ?? props.predictedAqi ?? risk.currentAqi);
      const score = priorityScore(props.riskLevel || props.severity, aqi);
      return {
        id: props.id || props.areaId || `${layer.layerId || layer.layerType || "hotspot"}-${index}`,
        area: firstText([props.areaName, props.locality, props.zoneName, props.name, props.label], "Unnamed hotspot"),
        currentAqi: aqi,
        riskLevel: enumLabel(props.riskLevel || props.severity, aqiRiskLabel(aqi)),
        forecast: props.forecastAqi || props.predictedAqi ? `Expected AQI ${Math.round(Number(props.forecastAqi || props.predictedAqi))}` : forecast.available ? `Peak ${risk.peakForecastAqi ?? "unavailable"} AQI` : "Forecast evidence not available",
        likelySource: firstText([props.likelySource, props.source, attribution.leadingSource?.sourceLabel], "Source evidence not available"),
        affectedPeople: firstText([props.affectedPopulation, props.populationExposed, props.population, props.sensitiveReceivers, sensitiveLocationSummary(props, decision)], "Sensitive locations and vulnerable groups are shown; population exposure estimation is not yet connected."),
        recommendedAction: firstText([props.recommendedAction, props.recommendation, props.action, enforcement.title], "Continue monitoring"),
        agency: responsibleUnit(props.agency || props.responsibleAgency || enforcement.agencies[0], enforcement.agencyStatus),
        evidence: firstText([props.reason, props.evidenceSummary, props.dominantEvidence, props.datasetsUsed], "Hotspot geometry returned without a detailed evidence note."),
        status: "Suggested",
        priority: priorityLabel(score),
        priorityScore: score,
      };
    }));

  const rows = hotspotRows.length > 0 ? hotspotRows : [{
    id: "selected-area",
    area: firstText([
      decision?.forecast?.stationName,
      decision?.environmentalSignals?.stationName,
      decision?.city?.displayName,
      decision?.cityName,
    ], "Selected area"),
    currentAqi: risk.currentAqi,
    riskLevel: risk.riskLevel,
    forecast: forecast.available ? `Peak ${risk.peakForecastAqi ?? "unavailable"} AQI over ${forecast.horizonCount} horizons` : "Forecast evidence not available",
    likelySource: attribution.leadingSource?.sourceLabel || "Source evidence not available",
    affectedPeople: sensitiveLocationSummary({}, decision),
    recommendedAction: enforcement.title,
    agency: responsibleUnit(enforcement.agencyStatus),
    evidence: cleanSentence(risk.decisionSummary),
    status: "Monitoring",
    priority: enforcement.priority,
    priorityScore: priorityScore(enforcement.priority, risk.currentAqi),
  }];

  return rows.sort((a, b) => b.priorityScore - a.priorityScore);
}

export function normalizeActionQueue(decision) {
  const enforcement = normalizeEnforcement(decision);
  const areaRows = normalizeAreaIntelligence(decision);
  const candidates = enforcement.recommendations.length > 0
    ? enforcement.recommendations
    : array(decision?.priorityActions);
  const rows = candidates.map((item, index) => ({
    id: item?.id || item?.actionId || `action-${index}`,
    title: actionTitle(item, enforcement.title),
    priority: enumLabel(item?.priorityLevel || item?.priority || enforcement.priority, enforcement.priority),
    agency: responsibleUnit(item?.responsibleAgency || item?.agency || enforcement.agencies[0], enforcement.agencyStatus),
    area: firstText([item?.area, item?.zone, item?.locality, areaRows[0]?.area], "Selected area"),
    status: enumLabel(item?.status, "Suggested"),
    actionWindow: firstText([item?.actionWindow, item?.deadline, item?.timeWindow], enforcement.actionWindow),
    reason: actionReason(item, enforcement.reason),
    confidenceText: percentText(item?.confidence ?? enforcement.confidence),
  }));

  if (rows.length > 0) return rows;
  return [{
    id: "routine-monitoring",
    title: enforcement.title || "Continue monitoring",
    priority: enforcement.priority || "Low",
    agency: responsibleUnit(enforcement.agencyStatus),
    area: areaRows[0]?.area || "Selected area",
    status: "Monitoring",
    actionWindow: enforcement.actionWindow,
    reason: enforcement.reason,
    confidenceText: enforcement.confidenceText,
  }];
}

export function normalizeOperationalAlerts(decision) {
  const forecast = normalizeForecast(decision?.forecast);
  const risk = normalizeRiskDecision(decision);
  const attribution = normalizeAttribution(decision?.attribution);
  const areaRows = normalizeAreaIntelligence(decision);
  const alerts = [];
  const peakForecast = risk.peakForecastAqi;
  const currentAqi = risk.currentAqi;

  if (currentAqi !== null) {
    alerts.push({
      id: "current-condition",
      type: "Current AQI",
      title: `${aqiRiskLabel(currentAqi)} air quality now`,
      severity: aqiRiskLabel(currentAqi),
      area: areaRows[0]?.area || "Selected area",
      agency: responsibleUnit(areaRows[0]?.agency, "Air Quality Command Center"),
      status: "New",
      reason: `Current AQI is ${Math.round(currentAqi)}.`,
      currentAqi,
      forecastAqi: peakForecast,
      recommendedAction: areaRows[0]?.recommendedAction || "Continue monitoring",
      confidenceText: risk.available ? "Current AQI evidence available" : "Evidence not available",
      createdAt: decision?.generatedAt,
    });
  }

  if (forecast.available) {
    const highest = forecast.validPoints
      .slice()
      .sort((a, b) => (numberOrNull(b.predictedAqi) ?? -1) - (numberOrNull(a.predictedAqi) ?? -1))[0];
    alerts.push({
      id: "forecast-outlook",
      type: "Forecast",
      title: `${aqiRiskLabel(peakForecast)} forecast outlook`,
      severity: aqiRiskLabel(peakForecast),
      area: areaRows[0]?.area || "Selected area",
      agency: "Air Quality Command Center",
      status: "New",
      reason: highest ? `${highest.key} forecast AQI is ${Math.round(numberOrNull(highest.predictedAqi) ?? 0)}.` : "Forecast horizon evidence is available.",
      currentAqi,
      forecastAqi: numberOrNull(highest?.predictedAqi),
      recommendedAction: peakForecast > 100 ? "Prepare public advisory and review enforcement queue." : "Continue routine monitoring.",
      confidenceText: percentText(highest?.confidence),
      horizon: highest?.key,
      createdAt: decision?.generatedAt,
    });
  }

  if (attribution.leadingSource) {
    alerts.push({
      id: "source-analysis",
      type: "Source",
      title: `${attribution.leadingSource.sourceLabel} is the leading explained source`,
      severity: attribution.leadingSource.contribution >= 30 ? "Elevated" : "Watch",
      area: areaRows[0]?.area || "Selected area",
      agency: responsibleUnit(areaRows[0]?.agency),
      status: "New",
      reason: `${attribution.leadingSource.contributionText} contribution with ${attribution.leadingSource.confidenceText} confidence.`,
      currentAqi,
      forecastAqi: peakForecast,
      recommendedAction: areaRows[0]?.recommendedAction || "Review source evidence.",
      confidenceText: attribution.leadingSource.confidenceText,
      createdAt: decision?.generatedAt,
    });
  }

  areaRows.slice(0, 3).forEach((area, index) => {
    if (area.id === "selected-area") return;
    alerts.push({
      id: `area-${area.id || index}`,
      type: "Area",
      title: `${area.area} needs attention`,
      severity: area.riskLevel,
      area: area.area,
      agency: area.agency,
      status: "New",
      reason: area.evidence,
      currentAqi: area.currentAqi,
      forecastAqi: peakForecast,
      recommendedAction: area.recommendedAction,
      confidenceText: "Layer evidence",
      createdAt: decision?.generatedAt,
    });
  });

  return alerts;
}

export function buildSituationSummary(decision) {
  const forecast = normalizeForecast(decision?.forecast);
  const risk = normalizeRiskDecision(decision);
  const attribution = normalizeAttribution(decision?.attribution);
  const enforcement = normalizeEnforcement(decision);
  const areas = normalizeAreaIntelligence(decision);
  const alerts = normalizeOperationalAlerts(decision);
  const unknown = unknownContribution(decision?.attribution);
  const peakText = risk.peakForecastAqi == null ? "forecast unavailable" : `peak forecast AQI ${Math.round(risk.peakForecastAqi)}`;
  const sourceText = attribution.leadingSource
    ? `${attribution.leadingSource.sourceLabel} is the leading explained source at ${attribution.leadingSource.contributionText} contribution with ${attribution.leadingSource.confidenceText} source-specific confidence. Overall attribution confidence is ${attribution.confidenceText}${unknown ? `, and ${unknown.contributionText} remains unexplained` : ""}.`
    : "source evidence unavailable";
  return {
    what: risk.currentAqi == null ? "Current air quality evidence is not available." : `${aqiRiskLabel(risk.currentAqi)} air quality now, AQI ${Math.round(risk.currentAqi)}.`,
    where: areas[0]?.area || "Selected area",
    why: sourceText,
    forecast: forecast.available ? `${risk.trend}; ${peakText}.` : "Forecast evidence not available.",
    affected: areas[0]?.affectedPeople || "Exposure estimate not available",
    action: enforcement.title,
    agency: responsibleUnit(enforcement.agencyStatus),
    evidence: forecast.available ? `${forecast.horizonCount} forecast horizons, ${attribution.sources.length} source rows, ${areas.length} area row${areas.length === 1 ? "" : "s"}.` : `${attribution.sources.length} source rows, ${areas.length} area row${areas.length === 1 ? "" : "s"}.`,
    status: alerts.length > 0 ? `${alerts.length} operational watch item${alerts.length === 1 ? "" : "s"}` : "No active watch items",
    currentAqi: risk.currentAqi,
    peakForecastAqi: risk.peakForecastAqi,
    trend: risk.trend,
  };
}

export function normalizeStationCatalogue(decision) {
  const signals = decision?.environmentalSignals || {};
  const forecast = decision?.forecast || {};
  const stationName = firstText([
    forecast.stationName,
    array(forecast.forecasts).find((point) => point?.stationName)?.stationName,
    signals.stationName,
  ], "");
  if (!stationName) return [];
  return [{
    id: forecast.stationKey || signals.stationKey || stationName,
    name: stationName,
    city: firstText([forecast.city, decision?.cityName, decision?.city?.cityName], "Selected city"),
    provider: firstText([signals.provider, signals.aqiProvider, forecast.currentProvider], "AQI provider not reported"),
    currentAqi: numberOrNull(decision?.currentAQI ?? signals.currentAqi ?? signals.aqi),
    primaryPollutant: firstText([signals.dominantPollutant, signals.primaryPollutant], "Primary pollutant not reported"),
    updatedAt: signals.observedAt || signals.timestamp || decision?.generatedAt,
    freshness: enumLabel(signals.freshnessStatus || signals.dataFreshness || "CURRENT", "Freshness not reported"),
    status: signals.aqiAvailable === false ? "Needs attention" : "Live",
    latitude: forecast.stationLatitude ?? signals.stationLatitude ?? decision?.latitude,
    longitude: forecast.stationLongitude ?? signals.stationLongitude ?? decision?.longitude,
  }];
}

export function normalizeDecisionSnapshot(decision, city = null, timeline = null) {
  const forecast = normalizeForecast(decision?.forecast);
  const attribution = normalizeAttribution(decision?.attribution);
  const enforcement = normalizeEnforcement(decision);
  const areas = normalizeAreaIntelligence(decision);
  const actions = normalizeActionQueue(decision);
  const alerts = normalizeOperationalAlerts(decision);
  const stations = normalizeStationCatalogue(decision);
  const signals = decision?.environmentalSignals || {};
  const lat = city?.latitude ?? city?.lat ?? decision?.latitude ?? signals.latitude;
  const lng = city?.longitude ?? city?.lng ?? city?.lon ?? decision?.longitude ?? signals.longitude;
  return {
    city,
    station: stations[0] || null,
    coordinates: Number.isFinite(Number(lat)) && Number.isFinite(Number(lng)) ? { latitude: Number(lat), longitude: Number(lng) } : null,
    snapshotId: decision?.snapshotId,
    currentAqi: numberOrNull(decision?.currentAQI ?? signals.currentAqi ?? signals.aqi),
    currentStandard: signals.aqiStandard || decision?.currentStandard || forecast.standard,
    currentProvider: signals.provider || signals.aqiProvider || decision?.forecast?.currentProvider,
    pollutants: signals.pollutants || {},
    forecasts: forecast.points,
    forecastResult: decision?.forecast,
    forecast,
    attribution,
    hotspots: areas.filter((area) => area.id !== "selected-area"),
    areas,
    alerts,
    actions,
    advisories: array(decision?.advisories?.advisories || decision?.advisories?.recommendations || decision?.advisories?.actions),
    enforcement,
    generatedAt: decision?.generatedAt,
    timelineFrameCount: array(timeline?.frames).length,
  };
}
