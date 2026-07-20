import { useState, useEffect } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { getWardDisplay } from "../services/api";
import AqiBadge from "../components/AqiBadge";

export default function KioskDisplay() {
  const { wardId } = useParams();
  const [searchParams] = useSearchParams();
  
  const cityId = searchParams.get("cityId") || "UNKNOWN_PLACE";
  const language = searchParams.get("language") || "en";
  
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const result = await getWardDisplay(wardId, cityId, language);
        setData(result);
      } catch (err) {
        console.error(err);
        setError("Failed to load display data");
      }
    };
    
    fetchData();
    const interval = setInterval(fetchData, 60000); // refresh every minute
    
    return () => clearInterval(interval);
  }, [wardId, cityId, language]);

  if (error) {
    return (
      <div style={{ height: '100vh', width: '100vw', display: 'flex', justifyContent: 'center', alignItems: 'center', background: '#111827', color: 'white' }}>
        <h1>{error}</h1>
      </div>
    );
  }

  if (!data) {
    return (
      <div style={{ height: '100vh', width: '100vw', display: 'flex', justifyContent: 'center', alignItems: 'center', background: '#111827', color: 'white' }}>
        <h1>Loading {wardId}...</h1>
      </div>
    );
  }

  return (
    <div style={{ 
      height: '100vh', 
      width: '100vw', 
      background: 'black',
      color: 'white',
      display: 'flex',
      flexDirection: 'column',
      fontFamily: 'system-ui, sans-serif'
    }}>
      {/* Header */}
      <div style={{ padding: '2rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '2px solid rgba(255,255,255,0.1)' }}>
        <div>
          <h1 style={{ margin: 0, fontSize: '3rem' }}>Ward: {data.wardId}</h1>
          <p style={{ margin: '0.5rem 0 0 0', fontSize: '1.5rem', opacity: 0.7 }}>City: {data.cityId}</p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '2rem' }}>
          <span className="status-badge" style={{ background: '#3b82f6', fontSize: '1.2rem', padding: '0.5rem 1rem' }}>Prototype Kiosk</span>
          <div style={{ fontSize: '1.5rem', opacity: 0.7 }}>{new Date().toLocaleTimeString()}</div>
        </div>
      </div>

      {/* Main Content */}
      <div style={{ flex: 1, display: 'flex', padding: '4rem' }}>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
          <h2 style={{ fontSize: '2rem', margin: '0 0 2rem 0', opacity: 0.8 }}>Current Air Quality</h2>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: '2rem' }}>
            <span style={{ fontSize: '12rem', fontWeight: 'bold', lineHeight: 1 }}>{data.currentAqi}</span>
            <span style={{ fontSize: '4rem', opacity: 0.8 }}>AQI</span>
          </div>
          <div style={{ marginTop: '2rem', display: 'inline-block', padding: '1rem 2rem', borderRadius: '12px', background: data.displayColor, color: 'white', fontSize: '2.5rem', fontWeight: 'bold' }}>
            Risk Level: {data.riskLevel}
          </div>
          
          {data.riskScore > 0 && (
            <div style={{ marginTop: '2rem', fontSize: '1.5rem', opacity: 0.8 }}>
              Base Risk Score: <strong>{data.riskScore}/100</strong>
            </div>
          )}
        </div>
        
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', borderLeft: '2px solid rgba(255,255,255,0.1)', paddingLeft: '4rem' }}>
          <h2 style={{ fontSize: '2rem', margin: '0 0 2rem 0', opacity: 0.8 }}>Public Health Advisory</h2>
          <div style={{ fontSize: '3rem', fontWeight: 'bold', lineHeight: 1.2, color: data.displayColor }}>
            {data.topAdvisoryLine}
          </div>
          
          <div style={{ marginTop: '4rem', padding: '2rem', background: 'rgba(255,255,255,0.05)', borderRadius: '16px' }}>
            <h3 style={{ margin: '0 0 1rem 0', fontSize: '1.5rem', opacity: 0.8 }}>Forecast Peak Exposure</h3>
            <div style={{ fontSize: '2rem' }}>
              <strong>{data.forecastPeakAqi} AQI</strong> expected at <strong>{data.forecastPeakAt}</strong>
            </div>
          </div>
          
          <div style={{ marginTop: 'auto', fontSize: '1rem', opacity: 0.5, textAlign: 'right' }}>
            Last updated: {new Date(data.updatedAt).toLocaleString()} • Source: {data.source}
          </div>
        </div>
      </div>
    </div>
  );
}
