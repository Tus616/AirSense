import { useEffect, useMemo, useRef, useState } from "react";
import { searchPlaces } from "../../services/decisionApi";
import { defaultCityMetadata, makeCityId, toDisplayText } from "./decisionUtils";

function resultToCity(result) {
  const cityName = result?.cityName || result?.displayName?.split(",")?.[0]?.trim() || "Selected Place";
  return {
    cityId: makeCityId(cityName, result?.placeId),
    cityName,
    displayName: result?.displayName || cityName,
    placeId: result?.placeId || "",
    latitude: Number(result?.latitude),
    longitude: Number(result?.longitude),
    state: result?.state || "",
    country: result?.country || "",
  };
}

export default function CitySelector({ city, onChange, loading }) {
  const [query, setQuery] = useState(city?.displayName || city?.cityName || "");
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const [open, setOpen] = useState(false);
  const requestRef = useRef(null);
  const requestIdRef = useRef(0);

  const selectedLabel = useMemo(
    () => city?.displayName || city?.cityName || defaultCityMetadata.displayName || "",
    [city]
  );

  useEffect(() => {
    setQuery(selectedLabel);
  }, [selectedLabel]);

  useEffect(() => {
    const trimmed = query.trim();
    if (requestRef.current) {
      requestRef.current.abort();
      requestRef.current = null;
    }

    if (trimmed.length < 2 || trimmed === selectedLabel) {
      setResults([]);
      setSearching(false);
      setError("");
      setStatus("");
      return;
    }

    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;
    setSearching(true);
    setError("");
    setStatus("");

    const timer = window.setTimeout(async () => {
      const controller = new AbortController();
      requestRef.current = controller;
      try {
        const response = await searchPlaces(trimmed, { signal: controller.signal });
        if (requestIdRef.current !== requestId) return;
        const nextResults = Array.isArray(response?.results) ? response.results : [];
        setResults(nextResults);
        setStatus(response?.status || "");
        setOpen(true);
        if (response?.status === "RATE_LIMITED") {
          setError(response?.message || "Search is rate limited. Please wait and try again.");
        } else if (nextResults.length === 0) {
          setError(response?.message || "No matching place found.");
        } else {
          setError("");
        }
      } catch (err) {
        if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
        if (requestIdRef.current !== requestId) return;
        setResults([]);
        setStatus("FAILED");
        setOpen(true);
        setError(err?.response?.data?.message || err?.message || "Place search provider unavailable.");
      } finally {
        if (requestRef.current === controller) {
          requestRef.current = null;
        }
        if (requestIdRef.current === requestId) {
          setSearching(false);
        }
      }
    }, 300);

    return () => {
      window.clearTimeout(timer);
      if (requestRef.current) {
        requestRef.current.abort();
        requestRef.current = null;
      }
    };
  }, [query, selectedLabel]);

  function selectResult(result) {
    const nextCity = resultToCity(result);
    if (!Number.isFinite(nextCity.latitude) || !Number.isFinite(nextCity.longitude)) {
      setError("Selected place does not include valid coordinates.");
      return;
    }
    setOpen(false);
    setResults([]);
    setError("");
    setStatus("");
    setQuery(nextCity.displayName);
    onChange(nextCity);
  }

  return (
    <div className="decision-city-search">
      <label>
        <span>City / Place</span>
        <input
          type="search"
          value={query}
          disabled={loading}
          placeholder="Search any city or place"
          onFocus={() => setOpen(true)}
          onChange={(event) => setQuery(event.target.value)}
        />
      </label>
      <div className="decision-city-search__meta">
        <span>{toDisplayText(city?.state || city?.country, "OpenStreetMap Nominatim search")}</span>
        {searching && <b>Searching</b>}
        {status === "RATE_LIMITED" && <b>Rate limited</b>}
      </div>
      {open && (results.length > 0 || error) && (
        <div className="decision-city-results">
          {results.map((result) => (
            <button
              key={result.placeId || `${result.latitude}-${result.longitude}-${result.displayName}`}
              type="button"
              disabled={loading}
              onClick={() => selectResult(result)}
            >
              <strong>{result.cityName || result.displayName}</strong>
              <span>{result.displayName}</span>
            </button>
          ))}
          {error && <p>{error}</p>}
        </div>
      )}
    </div>
  );
}
