import { useEffect, useMemo, useRef, useState } from "react";
import L from "leaflet";
import {
  CircleMarker,
  GeoJSON,
  LayerGroup,
  LayersControl,
  MapContainer,
  Marker,
  Popup,
  TileLayer,
  Tooltip,
  useMap,
} from "react-leaflet";
import "leaflet/dist/leaflet.css";
import markerIcon2x from "leaflet/dist/images/marker-icon-2x.png";
import markerIcon from "leaflet/dist/images/marker-icon.png";
import markerShadow from "leaflet/dist/images/marker-shadow.png";
import { getGeoSpatialIntelligence } from "../../services/decisionApi";
import { asArray, centerFromCity, GIS_LAYERS, labelize, toDisplayText } from "./decisionUtils";
import { enumLabel } from "../../services/decisionNormalization";

const DEFAULT_LAYERS = GIS_LAYERS.reduce((acc, layer) => ({ ...acc, [layer.id]: true }), {});
const OSM_TILE_URL = "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png";

L.Icon.Default.mergeOptions({
  iconRetinaUrl: markerIcon2x,
  iconUrl: markerIcon,
  shadowUrl: markerShadow,
});

export default function GisDecisionMap({ city, geoSpatialOverride, snapshot }) {
  const [layers, setLayers] = useState(DEFAULT_LAYERS);
  const [geoSpatial, setGeoSpatial] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [tileStatus, setTileStatus] = useState("idle");
  const [tileKey, setTileKey] = useState(0);
  const requestRef = useRef(null);
  const shellRef = useRef(null);

  useEffect(() => {
    const suppressLeafletTilePositionRace = (event) => {
      if (String(event?.message || "").includes("_leaflet_pos")) {
        event.preventDefault();
        event.stopImmediatePropagation?.();
      }
    };
    window.addEventListener("error", suppressLeafletTilePositionRace, true);
    return () => window.removeEventListener("error", suppressLeafletTilePositionRace, true);
  }, []);

  const fetchGeoSpatial = async () => {
    if (requestRef.current) requestRef.current.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    if (geoSpatialOverride) {
      setGeoSpatial(geoSpatialOverride);
      setLoading(false);
      setError("");
      return;
    }
    setLoading(true);
    setError("");
    setGeoSpatial(null);
    try {
      const data = await getGeoSpatialIntelligence(city, { signal: controller.signal });
      setGeoSpatial(data && typeof data === "object" ? data : null);
    } catch (err) {
      if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
      setGeoSpatial(null);
      setError(err?.response?.data?.message || err?.message || "Geospatial API request failed.");
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null;
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    if (geoSpatialOverride) {
      setGeoSpatial(geoSpatialOverride);
      setLoading(false);
      setError("");
      return undefined;
    }
    fetchGeoSpatial();
    return () => {
      if (requestRef.current) requestRef.current.abort();
    };
  }, [city, geoSpatialOverride]);

  const visibleLayerTypes = useMemo(() => {
    const types = new Set();
    GIS_LAYERS.forEach((layer) => {
      if (layers[layer.id]) asArray(layer.layerTypes).forEach((type) => types.add(type));
    });
    return types;
  }, [layers]);

  const backendLayers = useMemo(
    () => asArray(geoSpatial?.layers).filter((layer) => visibleLayerTypes.has(layer.layerType || layer.layerId)),
    [geoSpatial, visibleLayerTypes]
  );
  const hotspotFeatureCount = useMemo(
    () => backendLayers
      .filter((layer) => String(layer.layerType || layer.layerId) === "AQI_HOTSPOTS")
      .reduce((count, layer) => count + featureCollection(layer).features.length, 0),
    [backendLayers],
  );

  const center = useMemo(() => {
    const backendCenter = geoSpatial?.metadata?.center;
    if (backendCenter?.latitude && backendCenter?.longitude) {
      return [Number(backendCenter.latitude), Number(backendCenter.longitude)];
    }
    const fallback = centerFromCity(city);
    return fallback ? [fallback.lat, fallback.lng] : null;
  }, [geoSpatial, city]);

  const snapshotSignals = snapshot?.environmentalSignals || {};
  const snapshotStatus = snapshotSignals.aqiAvailable ? "Live evidence" : "Evidence not available";

  const toggleLayer = (layerId) => {
    setLayers((current) => ({ ...current, [layerId]: !current[layerId] }));
  };

  const retryMap = () => {
    setTileStatus("idle");
    setTileKey((value) => value + 1);
    fetchGeoSpatial();
  };

  const toggleFullscreen = () => {
    const element = shellRef.current;
    if (!element) return;
    if (document.fullscreenElement) {
      document.exitFullscreen?.();
    } else {
      element.requestFullscreen?.();
    }
  };

  return (
    <section className="decision-map-panel">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">GIS Intelligence</span>
          <h2>Urban Air Quality Layers</h2>
        </div>
        <div className="decision-map-actions">
          <button className="decision-button" type="button" onClick={toggleFullscreen} aria-label="Open map fullscreen">
            Fullscreen
          </button>
          <button className="decision-button" type="button" onClick={retryMap} disabled={loading || Boolean(geoSpatialOverride)} aria-label="Refresh map layers">
            {loading ? "Loading" : "Retry map"}
          </button>
        </div>
      </div>

      <div className="decision-layer-toggles">
        {GIS_LAYERS.map((layer) => (
          <label key={layer.id} className={layers[layer.id] ? "is-active" : ""}>
            <input type="checkbox" checked={Boolean(layers[layer.id])} onChange={() => toggleLayer(layer.id)} />
            <span>{layer.label}</span>
          </label>
        ))}
      </div>
      <p className="decision-panel__note">
        Snapshot: {toDisplayText(snapshot?.snapshotId, "unavailable")}
        {" | "}Location: {toDisplayText(snapshot?.locationHash, "unavailable")}
        {" | "}AQI source: {toDisplayText(snapshotSignals.stationName, "source unavailable")}
          {" | "}Observed: {toDisplayText(snapshotSignals.observedAt, "unavailable")}
          {" | "}Fetched: {toDisplayText(snapshot?.generatedAt, "unavailable")}
        {" | "}Status: {snapshotStatus}
      </p>

      <div className="decision-map-shell" ref={shellRef}>
        {loading ? (
          <MapFallback title="Loading map layers" message="Fetching backend GeoJSON intelligence layers." loading />
        ) : error ? (
          <MapFallback title="Map layers unavailable" message={error} onRetry={retryMap} retryDisabled={Boolean(geoSpatialOverride)} />
        ) : !geoSpatial ? (
          <MapFallback title="No geospatial response" message="The backend did not return a geospatial intelligence object." onRetry={retryMap} retryDisabled={Boolean(geoSpatialOverride)} />
        ) : !center ? (
          <MapFallback title="Map center unavailable" message="Select a place with valid coordinates to render GIS layers." />
        ) : (
          <>
            <MapContainer center={center} zoom={11} minZoom={8} maxZoom={16} scrollWheelZoom>
              <MapSync center={center} />
              <TileLayer
                key={tileKey}
                attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                url={OSM_TILE_URL}
                eventHandlers={{
                  tileload: () => setTileStatus((status) => (status === "idle" ? "available" : status)),
                  tileerror: () => setTileStatus("unavailable"),
                }}
              />
              <LayersControl position="topright">
                <LayersControl.Overlay checked name="Selected station">
                  <LayerGroup>
                    <CircleMarker center={center} radius={11} pathOptions={{ color: "#ffffff", weight: 3, fillColor: "#111827", fillOpacity: 0.9 }}>
                      <Tooltip direction="top">Selected station: {toDisplayText(snapshotSignals.stationName, "selected location")}</Tooltip>
                    </CircleMarker>
                    <Marker position={center}>
                      <Popup>
                        <strong>{toDisplayText(snapshotSignals.stationName, "Selected location")}</strong>
                        <br />
                        AQI source: {toDisplayText(snapshotSignals.aqiProvider || snapshotSignals.primaryAqiProvider, "unavailable")}
                      </Popup>
                    </Marker>
                  </LayerGroup>
                </LayersControl.Overlay>
                {backendLayers.map((layer) => (
                  <LayersControl.Overlay checked name={labelize(layer.displayName || layer.layerType || layer.layerId, "Layer")} key={`${snapshot?.snapshotId || geoSpatial?.generatedAt || "live"}-${layer.layerId || layer.layerType}`}>
                    <GeoJSON
                      data={featureCollection(layer)}
                      pointToLayer={(feature, latlng) => pointToLayer(layer, feature, latlng)}
                      style={(feature) => featureStyle(layer, feature)}
                      onEachFeature={(feature, leafletLayer) => bindFeatureInteractions(layer, feature, leafletLayer)}
                    >
                      <Tooltip direction="top">{layerTooltip(layer)}</Tooltip>
                    </GeoJSON>
                  </LayersControl.Overlay>
                ))}
              </LayersControl>
            </MapContainer>
            {tileStatus === "unavailable" && (
              <div className="decision-map-tile-overlay" role="status">
                <span>Map tiles are temporarily unavailable.</span>
                <button className="decision-button" type="button" onClick={retryMap} disabled={loading || Boolean(geoSpatialOverride)}>
                  Retry Map
                </button>
              </div>
            )}
            {hotspotFeatureCount === 0 && (
              <div className="decision-map-tile-overlay decision-map-tile-overlay--notice" role="status">
                <span>No elevated hotspot zone was identified from the available evidence.</span>
              </div>
            )}
          </>
        )}
      </div>
      {geoSpatial && (
        <p className="decision-panel__note" style={{ fontSize: "0.75rem", opacity: 0.7 }}>
          Tiles: OpenStreetMap
          {" | "}Geometry: {toDisplayText(geoSpatial.geometrySource, "unavailable")}
          {geoSpatial.degradedMode ? " | Layer note: some optional layer geometries are limited" : ""}
          {" | "}Layers: {asArray(geoSpatial.layers).length}
          {" | "}Confidence: {toDisplayText(geoSpatial.summary?.confidence, "unavailable")}
        </p>
      )}
    </section>
  );
}

function MapSync({ center }) {
  const map = useMap();
  const mountedRef = useRef(true);
  const safeInvalidate = () => {
    const container = map.getContainer?.();
    if (!mountedRef.current || !container?._leaflet_id) return;
    try {
      map.invalidateSize();
    } catch {
      // Leaflet can race with route transitions while external tiles are failing.
    }
  };
  useEffect(() => {
    try {
      const zoom = Number.isFinite(map.getZoom()) ? Math.min(Math.max(map.getZoom(), 8), 16) : 11;
      map.setView(center, zoom, { animate: false });
    } catch {
      return undefined;
    }
    const timer = window.setTimeout(safeInvalidate, 0);
    return () => window.clearTimeout(timer);
  }, [center, map]);
  useEffect(() => {
    const container = map.getContainer();
    if (!container || typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(() => {
      window.requestAnimationFrame(safeInvalidate);
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, [map]);
  useEffect(() => {
    const invalidate = () => window.requestAnimationFrame(safeInvalidate);
    window.addEventListener("resize", invalidate);
    document.addEventListener("fullscreenchange", invalidate);
    const timer = window.setTimeout(invalidate, 260);
    return () => {
      window.removeEventListener("resize", invalidate);
      document.removeEventListener("fullscreenchange", invalidate);
      window.clearTimeout(timer);
    };
  }, [map]);
  useEffect(() => () => {
    mountedRef.current = false;
  }, []);
  return null;
}

function featureCollection(layer) {
  const directFeatures = asArray(layer?.features);
  const geoJsonFeatures = asArray(layer?.geoJson?.features);
  return {
    type: "FeatureCollection",
    features: geoJsonFeatures.length > 0 ? geoJsonFeatures : directFeatures,
  };
}

function pointToLayer(layer, feature, latlng) {
  const props = feature?.properties || {};
  const radiusMeters = Number(props.radiusMeters);
  if (Number.isFinite(radiusMeters) && radiusMeters > 0) {
    return L.circle(latlng, {
      radius: radiusMeters,
      color: colorFor(layer, props),
      opacity: 0.9,
      weight: 2,
      fillColor: colorFor(layer, props),
      fillOpacity: 0.22,
    });
  }
  return L.circleMarker(latlng, {
    radius: Math.max(6, Math.min(18, Math.round(pointRadius(layer, props) / 45))),
    color: "#ffffff",
    weight: 2,
    fillColor: colorFor(layer, props),
    fillOpacity: 0.84,
  });
}

function featureStyle(layer, feature) {
  const props = feature?.properties || {};
  const color = colorFor(layer, props);
  return {
    color,
    opacity: 0.9,
    weight: layer.layerType === "TRAFFIC_CORRIDORS" ? 5 : 2,
    fillColor: color,
    fillOpacity: polygonOpacity(layer),
  };
}

function layerTooltip(layer) {
  return [
    labelize(layer?.displayName || layer?.layerType, "Layer"),
    toDisplayText(layer?.metadata?.dataOrigin || layer?.dataOrigin, ""),
    toDisplayText(layer?.metadata?.note || layer?.evidenceSummary, ""),
  ].filter(Boolean).join(" | ");
}

function bindFeatureInteractions(layer, feature, leafletLayer) {
  const props = feature?.properties || {};
  const title = props.riskLevel
    ? `${labelize(props.riskLevel)} risk`
    : labelize(layer?.displayName || layer?.layerType, "Layer feature");
  const tooltip = [
    title,
    props.forecastAqi || props.predictedAqi ? `AQI ${props.forecastAqi || props.predictedAqi}` : "",
    props.confidence != null ? `Confidence ${Math.round(Number(props.confidence) * 100)}%` : "",
  ].filter(Boolean).join(" | ");
  leafletLayer.bindTooltip(tooltip);
  leafletLayer.bindPopup(featurePopupHtml(layer, props));
}

function featurePopupHtml(layer, props) {
  const rows = [
    ["Risk", props.riskLevel],
    ["Risk score", props.riskScore],
    ["Forecast AQI", props.forecastAqi || props.predictedAqi],
    ["Reason", props.reason || props.evidenceSummary],
    ["Evidence", toDisplayText(props.dominantEvidence || props.datasetsUsed, "")],
    ["Confidence", props.confidence != null ? `${Math.round(Number(props.confidence) * 100)}%` : ""],
    ["Action", props.recommendedAction || props.recommendation],
    ["Origin", enumLabel(props.dataOrigin || layer?.metadata?.dataOrigin, "Evidence not available")],
    ["Snapshot", props.snapshotId],
  ].filter(([, value]) => value !== null && value !== undefined && value !== "");
  return `
    <div class="decision-map-popup">
      <strong>${escapeHtml(labelize(layer?.displayName || layer?.layerType, "Map feature"))}</strong>
      ${rows.map(([label, value]) => `<div><span>${escapeHtml(label)}</span><b>${escapeHtml(toDisplayText(value, ""))}</b></div>`).join("")}
    </div>
  `;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function colorFor(layer, props) {
  const layerType = layer?.layerType || layer?.layerId;
  if (layerType?.includes("FORECAST")) return Number(props.predictedAqi) > 300 ? "#a93434" : Number(props.predictedAqi) > 200 ? "#d95d39" : "#d99b18";
  if (layerType === "AQI_HOTSPOTS") return Number(props.aqi) > 300 ? "#a93434" : "#d95d39";
  if (layerType === "TRAFFIC_CORRIDORS") return "#227093";
  if (layerType === "CONSTRUCTION_SITES") return "#d99b18";
  if (layerType === "INDUSTRIAL_ZONES") return "#a93434";
  if (layerType === "SENSITIVE_ZONES") return "#218c74";
  if (layerType === "WIND_VECTOR_LAYER") return "#218c74";
  if (layerType === "GREEN_COVER_LAYER") return "#2f9e44";
  if (layerType === "SATELLITE_EVIDENCE_LAYER") return "#596275";
  return "#6b7280";
}

function pointRadius(layer, props) {
  const type = layer?.layerType || "";
  const confidence = Number(props.confidence);
  const base = type === "SENSITIVE_ZONES" ? 340 : type === "INDUSTRIAL_ZONES" ? 420 : 380;
  return Math.round(base + (Number.isFinite(confidence) ? confidence * 220 : 0));
}

function polygonOpacity(layer) {
  const type = layer?.layerType || "";
  if (type === "GREEN_COVER_LAYER") return 0.20;
  if (type === "SATELLITE_EVIDENCE_LAYER") return 0.16;
  return 0.24;
}

function MapFallback({ title, message, onRetry, retryDisabled = false, loading = false }) {
  return (
    <div className={`decision-map-fallback ${loading ? "is-loading" : ""}`}>
      {loading && <div className="decision-map-skeleton" aria-hidden="true" />}
      <h3>{title}</h3>
      <p>{message}</p>
      {onRetry && (
        <button className="decision-button" type="button" onClick={onRetry} disabled={retryDisabled}>
          Retry map
        </button>
      )}
    </div>
  );
}
