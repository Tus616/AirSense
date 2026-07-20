import { useEffect, useState } from "react";
import { useSelector } from "react-redux";
import { Circle, MapContainer, TileLayer, Tooltip, useMapEvents } from "react-leaflet";
import "leaflet/dist/leaflet.css";
import { getAirQuality } from "../services/api";

const defaultCenter = { lat: 28.6448, lng: 77.1168 }; // Delhi

function ClickHandler({ onPick }) {
  useMapEvents({
    click(event) {
      onPick({ lat: event.latlng.lat, lng: event.latlng.lng });
    },
  });
  return null;
}

export default function MapView() {
  const { heatmapPoints, industrialPolluters, predictedPlume, gridForecast } = useSelector(
    (state) => state.sensorData
  );

  const [selectedLocation, setSelectedLocation] = useState(null);
  const [airQuality, setAirQuality] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Fetch air quality when location is selected
  useEffect(() => {
    if (!selectedLocation) return;
    const fetchAQ = async () => {
      setLoading(true);
      setError(null);
      try {
        const data = await getAirQuality(selectedLocation.lat, selectedLocation.lng);
        setAirQuality(data);
      } catch (e) {
        console.error(e);
        setError("Failed to fetch air quality.");
      } finally {
        setLoading(false);
      }
    };
    fetchAQ();
  }, [selectedLocation]);

  return (
    <div className="map-view" style={{ height: "600px", position: "relative" }}>
      <MapContainer
        center={[defaultCenter.lat, defaultCenter.lng]}
        zoom={11}
        style={{ width: "100%", height: "100%" }}
        scrollWheelZoom
      >
        <TileLayer
          attribution="&copy; OpenStreetMap contributors"
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <ClickHandler onPick={setSelectedLocation} />
        {selectedLocation && (
          <Circle
            center={[selectedLocation.lat, selectedLocation.lng]}
            radius={240}
            pathOptions={{ color: "#1d4ed8", fillColor: "#38bdf8", fillOpacity: 0.45 }}
          >
            <Tooltip direction="top" offset={[0, -4]} permanent>
              Selected location
            </Tooltip>
          </Circle>
        )}
      </MapContainer>

      {/* Air Quality Info Panel */}
      <div style={{ position: "absolute", top: 10, right: 10, background: "var(--color-bg)", padding: "10px", borderRadius: "4px", zIndex: 10 }}>
        {loading && <p>Loading...</p>}
        {error && <p style={{ color: "red" }}>{error}</p>}
        {airQuality && (
          <div>
            <h4>AQI: {airQuality.liveAirQuality?.aqi}</h4>
            <p>PM2.5: {airQuality.liveAirQuality?.pm25}</p>
            <p>PM10: {airQuality.liveAirQuality?.pm10}</p>
            <p>NO2: {airQuality.liveAirQuality?.no2}</p>
            <p>SO2: {airQuality.liveAirQuality?.so2}</p>
            <p>CO: {airQuality.liveAirQuality?.co}</p>
            <p>O3: {airQuality.liveAirQuality?.o3}</p>
            <p>NH3: {airQuality.liveAirQuality?.nh3}</p>
            <p>Risk Level: {airQuality.riskAnalysis?.level}</p>
          </div>
        )}
      </div>
    </div>
  );
}
