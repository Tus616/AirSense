import { useSelector } from "react-redux";
import {
  LineChart, Line, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from "recharts";
import { useState, useEffect } from "react";
import { formatDate } from "../utils/aqiUtils";
import { getAttributionLatest, runAttribution } from "../services/api";

const CHART_COLORS = ["#6366f1", "#8b5cf6", "#ec4899", "#f43f5e", "#f97316", "#eab308", "#22c55e"];

/**
 * Custom tooltip for charts
 */
function CustomTooltip({ active, payload, label }) {
  if (!active || !payload || payload.length === 0) return null;
  return (
    <div className="chart-tooltip glass-card">
      <p className="chart-tooltip__label">{label}</p>
      {payload.map((entry, i) => (
        <p key={i} style={{ color: entry.color }}>
          {entry.name}: <strong>{entry.value}</strong>
        </p>
      ))}
    </div>
  );
}

/**
 * Analytics charts panel for government dashboard
 * - Historical AQI trend line chart
 * - Sensor reliability bar chart
 * - Source attribution pie chart
 */
export default function AnalyticsCharts({ activeWard = "W01" }) {
  const { trends, reliability } = useSelector(
    (state) => state.sensorData
  );

  const [attributionData, setAttributionData] = useState(null);
  const [loadingAttr, setLoadingAttr] = useState(false);
  const [expandedSource, setExpandedSource] = useState(null);

  const fetchAttribution = async () => {
    try {
      setLoadingAttr(true);
      const data = await getAttributionLatest(activeWard);
      setAttributionData(data);
    } catch (e) {
      console.log("No attribution data found for", activeWard);
      setAttributionData(null);
    } finally {
      setLoadingAttr(false);
    }
  };

  useEffect(() => {
    fetchAttribution();
  }, [activeWard]);

  const handleRunAttribution = async () => {
    try {
      setLoadingAttr(true);
      const now = new Date();
      const past = new Date(now.getTime() - 24 * 60 * 60 * 1000);
      await runAttribution(activeWard, past.toISOString(), now.toISOString());
      await fetchAttribution();
    } catch (e) {
      console.error(e);
    } finally {
      setLoadingAttr(false);
    }
  };

  // Format trend data for chart
  const trendData = trends.map((t) => ({
    ...t,
    dateLabel: formatDate(t.date),
  }));

  // Format reliability data
  const reliabilityData = reliability.map((r) => ({
    name: r.name,
    uptime: r.uptime,
    completeness: r.dataCompleteness,
  }));

  return (
    <div className="analytics-charts">
      {/* Historical AQI Trend */}
      <div className="analytics-charts__panel glass-card">
        <h3 className="analytics-charts__title">
          <span className="analytics-charts__icon">📈</span>
          Historical AQI Trend (30 Days)
        </h3>
        <ResponsiveContainer width="100%" height={300}>
          <LineChart data={trendData} margin={{ top: 5, right: 20, bottom: 5, left: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
            <XAxis
              dataKey="dateLabel"
              stroke="#94a3b8"
              fontSize={11}
              interval={4}
            />
            <YAxis stroke="#94a3b8" fontSize={11} />
            <Tooltip content={<CustomTooltip />} />
            <Legend />
            <Line type="monotone" dataKey="aqi"  stroke="#6366f1" strokeWidth={2} dot={false} name="AQI" />
            <Line type="monotone" dataKey="pm25" stroke="#ec4899" strokeWidth={1.5} dot={false} name="PM2.5" />
            <Line type="monotone" dataKey="pm10" stroke="#f97316" strokeWidth={1.5} dot={false} name="PM10" />
            <Line type="monotone" dataKey="no2"  stroke="#22c55e" strokeWidth={1.5} dot={false} name="NO₂" />
          </LineChart>
        </ResponsiveContainer>
      </div>

      <div className="analytics-charts__row">
        {/* Sensor Reliability */}
        <div className="analytics-charts__panel analytics-charts__panel--half glass-card">
          <h3 className="analytics-charts__title">
            <span className="analytics-charts__icon">📡</span>
            Sensor Reliability
          </h3>
          <ResponsiveContainer width="100%" height={300}>
            <BarChart data={reliabilityData} margin={{ top: 5, right: 10, bottom: 5, left: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
              <XAxis dataKey="name" stroke="#94a3b8" fontSize={10} angle={-35} textAnchor="end" height={60} />
              <YAxis stroke="#94a3b8" fontSize={11} domain={[0, 100]} />
              <Tooltip content={<CustomTooltip />} />
              <Legend />
              <Bar dataKey="uptime" fill="#6366f1" name="Uptime %" radius={[4, 4, 0, 0]} />
              <Bar dataKey="completeness" fill="#8b5cf6" name="Data %" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Source Attribution (System 7) */}
        <div className="analytics-charts__panel analytics-charts__panel--half glass-card" style={{ display: 'flex', flexDirection: 'column' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h3 className="analytics-charts__title" style={{ margin: 0 }}>
              <span className="analytics-charts__icon">🏭</span>
              Multi-Modal Source Attribution
            </h3>
            <button className="btn btn--primary" onClick={handleRunAttribution} disabled={loadingAttr} style={{ padding: '0.25rem 0.75rem', fontSize: '0.85rem' }}>
              {loadingAttr ? 'Running...' : 'Run Engine'}
            </button>
          </div>
          
          <div style={{ marginTop: '1.5rem', flex: 1, overflowY: 'auto', paddingRight: '0.5rem' }}>
            {!attributionData ? (
              <div style={{ display: 'flex', height: '100%', alignItems: 'center', justifyContent: 'center', opacity: 0.6 }}>
                <p>No attribution data found. Run the engine.</p>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                <p style={{ margin: 0, fontSize: '0.9rem', opacity: 0.8 }}>
                  Generated: {new Date(attributionData.generatedAt).toLocaleString()}
                </p>
                {attributionData.rankedSources.map((source, idx) => (
                  <div key={idx} style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid var(--color-border)', borderRadius: '6px', padding: '1rem' }}>
                    
                    <div 
                      style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }}
                      onClick={() => setExpandedSource(expandedSource === idx ? null : idx)}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', width: '30%' }}>
                        <span style={{ fontSize: '1.2rem', fontWeight: 'bold' }}>#{idx + 1}</span>
                        <span style={{ textTransform: 'capitalize', fontWeight: '500' }}>{source.category}</span>
                      </div>
                      
                      <div style={{ flex: 1, margin: '0 1rem' }}>
                        <div style={{ width: '100%', height: '8px', background: 'var(--color-bg)', borderRadius: '4px', overflow: 'hidden' }}>
                          <div style={{ 
                            height: '100%', 
                            width: `${source.confidence}%`, 
                            background: source.confidence > 50 ? '#e93f33' : source.confidence > 25 ? '#f29c33' : '#6366f1' 
                          }}></div>
                        </div>
                      </div>
                      
                      <div style={{ width: '15%', textAlign: 'right', fontWeight: 'bold' }}>
                        {source.confidence}%
                      </div>
                    </div>

                    {expandedSource === idx && (
                      <div style={{ marginTop: '1rem', paddingTop: '1rem', borderTop: '1px solid var(--color-border)' }}>
                        <p style={{ margin: '0 0 0.5rem 0', fontSize: '0.85rem', color: 'var(--color-primary)' }}>Contributing Signals</p>
                        <ul style={{ margin: 0, paddingLeft: '1.5rem', fontSize: '0.9rem' }}>
                          {source.contributingSignals.map((sig, sidx) => (
                            <li key={sidx} style={{ marginBottom: '0.5rem' }}>
                              <strong>{sig.signalType}</strong> ({sig.strength}): {sig.summary}
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
