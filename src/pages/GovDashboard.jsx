import { useEffect, useState } from "react";
import { useSelector, useDispatch } from "react-redux";
import { fetchGovData, fetchPublicData } from "../store/sensorDataSlice";
import { setGovTab, setSelectedCity } from "../store/uiStateSlice";
import { 
  getPolicySimulations, runPolicySimulation, getSystemMetrics, 
  getEnforcementRecommendations, getEnforcementMetrics, updateRecommendationStatus,
  getCities, getComparedCities, getAdvisoryCoverage, getJudgeReadiness,
  runEvaluation, getLatestEvaluation
} from "../services/api";
import MapView from "../components/MapView";
import AnalyticsCharts from "../components/AnalyticsCharts";
import AqiBadge from "../components/AqiBadge";
import {
  LineChart, Line, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from "recharts";

/**
 * Government Dashboard — ADMIN-only data-dense operational view
 */
export default function GovDashboard() {
  const dispatch = useDispatch();
  const { govTab: activeTab, selectedCity } = useSelector((state) => state.uiState);
  const { citySummary, stations, govAdvisories, status, error } = useSelector((state) => state.sensorData);

  // City Data
  const [availableCities, setAvailableCities] = useState([]);
  const [citySnapshots, setCitySnapshots] = useState([]);

  // Policy Simulation State
  const [simulations, setSimulations] = useState([]);
  const [scenarioInput, setScenarioInput] = useState("");
  const [wardInput, setWardInput] = useState("");
  const [simulating, setSimulating] = useState(false);

  // System Metrics State
  const [metrics, setMetrics] = useState(null);
  const [forecastMetrics, setForecastMetrics] = useState(null);

  // Enforcement State
  const [enforcementQueue, setEnforcementQueue] = useState([]);
  const [enforcementMetrics, setEnforcementMetrics] = useState(null);
  const [expandedRecId, setExpandedRecId] = useState(null);
  const [enforcementFilter, setEnforcementFilter] = useState('ALL'); // 'ALL', 'OPEN', 'IN_PROGRESS', 'RESOLVED'

  // Advisory Coverage State
  const [advisoryCoverage, setAdvisoryCoverage] = useState(null);

  // Judge Readiness State
  const [judgeReport, setJudgeReport] = useState(null);

  useEffect(() => {
    getCities().then(setAvailableCities).catch(console.error);
  }, []);

  useEffect(() => {
    dispatch(fetchGovData(selectedCity));
    dispatch(fetchPublicData(selectedCity));
  }, [selectedCity, dispatch]);

  useEffect(() => {
    if (activeTab === "compare") {
      getComparedCities().then(setCitySnapshots).catch(console.error);
    } else if (activeTab === "simulations") {
      getPolicySimulations().then(setSimulations).catch(console.error);
    } else if (activeTab === "health") {
      getSystemMetrics().then(setMetrics).catch(console.error);
      getAdvisoryCoverage().then(setAdvisoryCoverage).catch(console.error);
      import("../services/api").then(api => {
          api.getForecastMetrics().then(setForecastMetrics).catch(console.error);
      });
    } else if (activeTab === "enforcement") {
      fetchEnforcementData();
    } else if (activeTab === "judge") {
      getJudgeReadiness().then(setJudgeReport).catch(console.error);
    }
  }, [activeTab]);

  const fetchEnforcementData = () => {
    getEnforcementRecommendations(null, selectedCity).then(data => {
      // Sort by priorityScore descending
      const sorted = data.sort((a, b) => b.priorityScore - a.priorityScore);
      setEnforcementQueue(sorted);
    }).catch(console.error);
    getEnforcementMetrics(selectedCity).then(setEnforcementMetrics).catch(console.error);
  };

  const handleStatusUpdate = async (id, newStatus) => {
    try {
      await updateRecommendationStatus(id, newStatus);
      fetchEnforcementData(); // refresh
    } catch (e) {
      console.error("Failed to update status", e);
    }
  };

  const handleRunSimulation = async (e) => {
    e.preventDefault();
    if (!scenarioInput.trim()) return;
    setSimulating(true);
    try {
      const result = await runPolicySimulation(scenarioInput, wardInput || null);
      setSimulations([result, ...simulations]);
      setScenarioInput("");
      setWardInput("");
    } catch (err) {
      console.error(err);
    } finally {
      setSimulating(false);
    }
  };

  const handleRunEvaluation = async () => {
    try {
      await runEvaluation();
      const updatedMetrics = await getSystemMetrics();
      setMetrics(updatedMetrics);
    } catch (err) {
      console.error("Failed to run evaluation", err);
    }
  };

  if (status === "loading" && stations.length === 0) {
    return (
      <div className="gov-dashboard" style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <p>Loading government intelligence data...</p>
      </div>
    );
  }

  if (status === "failed") {
    return (
      <div className="gov-dashboard" style={{ padding: '2rem', color: 'var(--color-severe)' }}>
        <h3>Error loading data</h3>
        <p>{error}</p>
      </div>
    );
  }

  const onlineCount = stations.filter((s) => s.status === "online").length;
  const offlineCount = stations.filter((s) => s.status === "offline").length;
  const maintenanceCount = stations.filter((s) => s.status === "maintenance").length;

  return (
    <div className="gov-dashboard">
      {/* Header */}
      <div className="gov-dashboard__header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div className="gov-dashboard__title-area">
          <h1>Municipal Command Center</h1>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginTop: '0.5rem' }}>
            <p className="gov-dashboard__subtitle" style={{ margin: 0 }}>Air Quality Operations</p>
            <select 
              value={selectedCity} 
              onChange={(e) => dispatch(setSelectedCity(e.target.value))}
              style={{ padding: '0.5rem', background: 'var(--color-surface)', color: 'var(--color-text)', border: '1px solid var(--color-border)', borderRadius: '4px', fontSize: '1rem', fontWeight: 'bold' }}
            >
              {availableCities.map(city => (
                <option key={city} value={city}>{city}</option>
              ))}
            </select>
          </div>
        </div>
        <div className="gov-dashboard__stats">
          <div className="gov-stat glass-card">
            <span className="gov-stat__label">City AQI</span>
            {citySummary ? <AqiBadge aqi={citySummary.aqi} size="md" /> : <span>-</span>}
          </div>
          <div className="gov-stat glass-card">
            <span className="gov-stat__label">Stations Online</span>
            <span className="gov-stat__value gov-stat__value--green">{onlineCount}</span>
          </div>
          <div className="gov-stat glass-card">
            <span className="gov-stat__label">Maintenance</span>
            <span className="gov-stat__value gov-stat__value--yellow">{maintenanceCount}</span>
          </div>
          <div className="gov-stat glass-card">
            <span className="gov-stat__label">Offline</span>
            <span className="gov-stat__value gov-stat__value--red">{offlineCount}</span>
          </div>
        </div>
      </div>

      {/* Tab Navigation */}
      <div className="gov-dashboard__tabs">
        <button
          className={`tab-btn ${activeTab === "map" ? "tab-btn--active" : ""}`}
          onClick={() => dispatch(setGovTab("map"))}
        >
          <span>🗺️</span> Map View
        </button>
        <button
          className={`tab-btn ${activeTab === "analytics" ? "tab-btn--active" : ""}`}
          onClick={() => dispatch(setGovTab("analytics"))}
        >
          <span>📊</span> Analytics
        </button>
        <button
          className={`tab-btn ${activeTab === "advisories" ? "tab-btn--active" : ""}`}
          onClick={() => dispatch(setGovTab("advisories"))}
        >
          <span>📋</span> Advisories
        </button>
        <button
          className={`tab-btn ${activeTab === "simulations" ? "tab-btn--active" : ""}`}
          onClick={() => dispatch(setGovTab("simulations"))}
        >
          <span>🧪</span> Simulations
        </button>
        <button
          className={`tab-btn ${activeTab === "enforcement" ? "tab-btn--active" : ""}`}
          onClick={() => dispatch(setGovTab("enforcement"))}
        >
          <span>⚖️</span> Enforcement Queue
        </button>
        <button
          className={`tab-btn ${activeTab === "compare" ? "tab-btn--active" : ""}`}
          onClick={() => dispatch(setGovTab("compare"))}
        >
          <span>🌐</span> City Compare
        </button>
        <button
          className={`tab-btn ${activeTab === "health" ? "tab-btn--active" : ""}`}
          onClick={() => dispatch(setGovTab("health"))}
        >
          <span>⚙️</span> System Health
        </button>
        <button
          className={`tab-btn ${activeTab === "judge" ? "tab-btn--active" : ""}`}
          onClick={() => dispatch(setGovTab("judge"))}
          style={{ marginLeft: 'auto', background: 'var(--color-primary)', color: 'white' }}
        >
          <span>🏆</span> Judge Readiness
        </button>
      </div>

      {/* Tab Content */}
      <div className="gov-dashboard__content">
        {activeTab === "map" && <MapView />}
        {activeTab === "analytics" && <AnalyticsCharts />}
        {activeTab === "advisories" && (
          <div className="gov-dashboard__advisories">
            <h2>Municipal Directives (AI-Generated)</h2>
            {(!govAdvisories || govAdvisories.length === 0) ? (
              <p>No active directives found.</p>
            ) : (
              <div className="directives-list">
                {govAdvisories.map(adv => (
                  <div key={adv.id || adv.wardId} className="directive-card glass-card">
                    <div className="directive-header">
                      <h3>Ward: {adv.wardId}</h3>
                      <span className={`status-badge ${adv.status === 'GENERATED' ? 'status-online' : 'status-offline'}`}>
                        {adv.status}
                      </span>
                    </div>
                    <div className="directive-metrics">
                      <span><strong>Peak AQI:</strong> {adv.peakAqi} ({adv.category})</span>
                      <span><strong>Primary Source:</strong> {adv.primarySource}</span>
                    </div>
                    {adv.municipalDirective && (
                      <div className="directive-body">
                        <h4>Enforcement Directive</h4>
                        <p>{adv.municipalDirective}</p>
                      </div>
                    )}
                    {adv.errorMessage && (
                      <div className="directive-error" style={{ color: 'var(--color-severe)', marginTop: '1rem' }}>
                        Error: {adv.errorMessage}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {activeTab === "simulations" && (
          <div className="gov-dashboard__simulations">
            <h2>Multi-Agent Policy Simulation</h2>
            <p style={{ opacity: 0.8, marginBottom: '2rem' }}>Evaluate hypothetical municipal interventions before enacting them. An AI agent debate is conducted between Health, Traffic, and Air Quality agents.</p>
            
            <form onSubmit={handleRunSimulation} className="simulation-form glass-card" style={{ marginBottom: '2rem', display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
              <input 
                type="text" 
                placeholder="Scenario (e.g. Implement odd-even rule for 5 days)" 
                value={scenarioInput}
                onChange={e => setScenarioInput(e.target.value)}
                style={{ flex: '1 1 300px', padding: '0.75rem', borderRadius: '4px', border: '1px solid var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}
              />
              <input 
                type="text" 
                placeholder="Ward ID (optional)" 
                value={wardInput}
                onChange={e => setWardInput(e.target.value)}
                style={{ flex: '0 1 150px', padding: '0.75rem', borderRadius: '4px', border: '1px solid var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}
              />
              <button type="submit" className="btn btn--primary" disabled={simulating}>
                {simulating ? "Simulating..." : "Run Simulation"}
              </button>
            </form>

            <div className="simulations-list">
              {simulations.length === 0 && !simulating && <p>No previous simulations found.</p>}
              {simulations.map(sim => (
                <div key={sim.id} className="simulation-card glass-card" style={{ marginBottom: '1.5rem', borderLeft: '4px solid #8e44ad' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <h3 style={{ margin: 0 }}>Scenario: {sim.scenario}</h3>
                    {sim.isSimulated ? <span className="status-badge status-offline">Mock Data</span> : <span className="status-badge status-online">Processed</span>}
                  </div>
                  <p><strong>Target:</strong> {sim.wardId ? `Ward ${sim.wardId}` : 'Citywide'}</p>
                  
                  <div className="agent-debate" style={{ marginTop: '1.5rem' }}>
                    <h4>Agent Debate Transcript</h4>
                    <div style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))', marginTop: '1rem' }}>
                      {sim.agentDebate.map((agent, i) => (
                        <div key={i} style={{ padding: '1rem', background: 'rgba(255,255,255,0.05)', borderRadius: '4px' }}>
                          <div style={{ fontWeight: 'bold', color: 'var(--color-primary)', marginBottom: '0.5rem' }}>{agent.agentRole}</div>
                          <p style={{ fontSize: '0.9rem', marginBottom: '0.5rem' }}>{agent.perspective}</p>
                          <div style={{ fontSize: '0.85rem', color: agent.impactScore.startsWith('+') ? 'var(--color-good)' : 'var(--color-severe)' }}>
                            Impact Score: {agent.impactScore}
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>

                  <div style={{ marginTop: '1.5rem', padding: '1rem', background: 'rgba(0,0,0,0.2)', borderRadius: '4px' }}>
                    <p style={{ marginBottom: '0.5rem' }}><strong>Recommended Action:</strong> {sim.recommendedAction}</p>
                    <p><strong>Risk Tradeoffs:</strong> {sim.riskTradeoffs}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {activeTab === "enforcement" && (
          <div className="gov-dashboard__enforcement">
            <h2>Enforcement Intelligence Queue</h2>
            <p style={{ opacity: 0.8, marginBottom: '2rem' }}>AI-prioritized deterministic enforcement actions based on 1km grid forecasts and cross-referenced evidence.</p>
            
            <div style={{ marginBottom: '1rem', display: 'flex', gap: '1rem', alignItems: 'center' }}>
              <span style={{ fontWeight: 'bold' }}>Filter Status:</span>
              <select 
                value={enforcementFilter} 
                onChange={(e) => setEnforcementFilter(e.target.value)}
                style={{ padding: '0.5rem', background: 'var(--color-surface)', color: 'var(--color-text)', border: '1px solid var(--color-border)', borderRadius: '4px' }}
              >
                <option value="ALL">All Actions</option>
                <option value="OPEN">Open</option>
                <option value="IN_PROGRESS">In Progress</option>
                <option value="RESOLVED">Resolved</option>
              </select>
            </div>
            
            {enforcementMetrics && (
              <div className="metrics-grid" style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(4, 1fr)', marginBottom: '2rem' }}>
                <div className="glass-card" style={{ padding: '1rem', textAlign: 'center' }}>
                  <h4>Total Queue</h4>
                  <div style={{ fontSize: '2rem', color: 'var(--color-primary)' }}>{enforcementMetrics.totalRecommendations}</div>
                </div>
                <div className="glass-card" style={{ padding: '1rem', textAlign: 'center' }}>
                  <h4>Open</h4>
                  <div style={{ fontSize: '2rem', color: 'var(--color-severe)' }}>{enforcementMetrics.openRecommendations}</div>
                </div>
                <div className="glass-card" style={{ padding: '1rem', textAlign: 'center' }}>
                  <h4>High Confidence</h4>
                  <div style={{ fontSize: '2rem', color: 'var(--color-good)' }}>{enforcementMetrics.highConfidenceActions}</div>
                </div>
                <div className="glass-card" style={{ padding: '1rem', textAlign: 'center' }}>
                  <h4>Avg Resolution (h)</h4>
                  <div style={{ fontSize: '2rem' }}>{enforcementMetrics.avgResolutionTimeHours.toFixed(1)}</div>
                </div>
              </div>
            )}

            <div className="enforcement-list" style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              {enforcementQueue.filter(rec => enforcementFilter === 'ALL' || rec.status === enforcementFilter).length === 0 && <p>No enforcement recommendations match the filter.</p>}
              {enforcementQueue
                .filter(rec => enforcementFilter === 'ALL' || rec.status === enforcementFilter)
                .map(rec => (
                <div key={rec.id} className="glass-card" style={{ borderLeft: `6px solid ${rec.priorityScore > 75 ? '#e93f33' : '#f29c33'}` }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', padding: '1rem' }}>
                    <div>
                      <h3 style={{ margin: '0 0 0.5rem 0' }}>{rec.actionType.replace(/_/g, ' ')}</h3>
                      <div style={{ fontSize: '0.9rem', color: 'var(--color-text-muted)' }}>Target: {rec.target} | Ward: {rec.wardId}</div>
                    </div>
                    <div style={{ textAlign: 'right' }}>
                      <div style={{ fontSize: '1.2rem', fontWeight: 'bold', color: rec.priorityScore > 75 ? '#e93f33' : '#f29c33' }}>
                        Priority: {rec.priorityScore.toFixed(1)}
                      </div>
                      <span className={`status-badge status-${rec.status.toLowerCase()}`}>{rec.status}</span>
                    </div>
                  </div>
                  
                  <div style={{ padding: '0 1rem 1rem' }}>
                    <p style={{ margin: '0 0 1rem 0' }}><strong>Expected Impact:</strong> {rec.expectedImpact}</p>
                    
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button 
                        className="btn btn--secondary" 
                        onClick={() => setExpandedRecId(expandedRecId === rec.id ? null : rec.id)}
                      >
                        {expandedRecId === rec.id ? 'Hide Evidence' : 'View Evidence'}
                      </button>
                      {rec.status === 'OPEN' && (
                        <button className="btn btn--primary" onClick={() => handleStatusUpdate(rec.id, 'IN_PROGRESS')}>Mark In Progress</button>
                      )}
                      {rec.status === 'IN_PROGRESS' && (
                        <button className="btn btn--primary" style={{ background: 'var(--color-good)' }} onClick={() => handleStatusUpdate(rec.id, 'RESOLVED')}>Resolve</button>
                      )}
                      {rec.status !== 'DISMISSED' && rec.status !== 'RESOLVED' && (
                        <button className="btn btn--secondary" onClick={() => handleStatusUpdate(rec.id, 'DISMISSED')}>Dismiss</button>
                      )}
                    </div>
                  </div>

                  {expandedRecId === rec.id && (
                    <div style={{ background: 'rgba(0,0,0,0.2)', padding: '1rem', borderTop: '1px solid var(--color-border)' }}>
                      <h4 style={{ marginTop: 0 }}>Cross-Referenced Evidence</h4>
                      <ul style={{ paddingLeft: '1.5rem', marginBottom: '1rem' }}>
                        {rec.evidence.map((ev, idx) => (
                          <li key={idx} style={{ marginBottom: '0.5rem' }}>
                            <strong>[{ev.type}]</strong> {ev.summary}
                          </li>
                        ))}
                      </ul>
                      <div>
                        <strong>Confidence Level:</strong> {(rec.confidence * 100).toFixed(0)}%
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {activeTab === "compare" && (
          <div className="gov-dashboard__compare">
            <h2>Multi-City Comparative Intelligence</h2>
            <p style={{ opacity: 0.8, marginBottom: '2rem' }}>Aggregate metrics comparing AQI burden, forecast risk, enforcement response, and advisory coverage across monitored cities.</p>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem', marginBottom: '2rem' }}>
              <div className="glass-card" style={{ padding: '1.5rem', textAlign: 'center' }}>
                <h4 style={{ margin: '0 0 0.5rem 0', color: 'var(--color-text-muted)' }}>Best AQI (30d Avg)</h4>
                <div style={{ fontSize: '1.5rem', fontWeight: 'bold' }}>
                  {citySnapshots.length > 0 ? citySnapshots.reduce((min, s) => s.averageAqi30d < min.averageAqi30d ? s : min, citySnapshots[0]).cityName : '-'}
                </div>
              </div>
              <div className="glass-card" style={{ padding: '1.5rem', textAlign: 'center' }}>
                <h4 style={{ margin: '0 0 0.5rem 0', color: 'var(--color-text-muted)' }}>Highest Pending Enforcement</h4>
                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: 'var(--color-severe)' }}>
                  {citySnapshots.length > 0 ? citySnapshots.reduce((max, s) => s.openEnforcementCount > max.openEnforcementCount ? s : max, citySnapshots[0]).cityName : '-'}
                </div>
              </div>
              <div className="glass-card" style={{ padding: '1.5rem', textAlign: 'center' }}>
                <h4 style={{ margin: '0 0 0.5rem 0', color: 'var(--color-text-muted)' }}>Best Compliance Rate</h4>
                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: 'var(--color-good)' }}>
                  {citySnapshots.length > 0 ? citySnapshots.reduce((max, s) => {
                    const rate1 = s.resolvedEnforcementCount / (s.resolvedEnforcementCount + s.openEnforcementCount || 1);
                    const rate2 = max.resolvedEnforcementCount / (max.resolvedEnforcementCount + max.openEnforcementCount || 1);
                    return rate1 > rate2 ? s : max;
                  }, citySnapshots[0]).cityName : '-'}
                </div>
              </div>
              <div className="glass-card" style={{ padding: '1.5rem', textAlign: 'center' }}>
                <h4 style={{ margin: '0 0 0.5rem 0', color: 'var(--color-text-muted)' }}>Highest Forecast Error</h4>
                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: 'var(--color-severe)' }}>
                  {citySnapshots.length > 0 ? citySnapshots.reduce((max, s) => s.forecastRmse > max.forecastRmse ? s : max, citySnapshots[0]).cityName : '-'}
                </div>
              </div>
            </div>

            <div style={{ display: 'grid', gap: '1.5rem', gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))' }}>
              <div className="glass-card" style={{ padding: '1.5rem' }}>
                <h3>30-Day AQI Trend</h3>
                <div style={{ height: '300px' }}>
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart>
                      <CartesianGrid strokeDasharray="3 3" opacity={0.2} />
                      <XAxis dataKey="day" opacity={0.5} />
                      <YAxis opacity={0.5} />
                      <Tooltip contentStyle={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }} />
                      <Legend />
                      {citySnapshots.map((snap, i) => (
                        <Line 
                          key={snap.cityId}
                          type="monotone" 
                          data={snap.aqiTrend?.map((val, idx) => ({ day: idx - 30, value: val })) || []} 
                          dataKey="value" 
                          name={snap.cityName}
                          stroke={["#6366f1", "#ec4899", "#22c55e", "#f97316"][i % 4]} 
                          strokeWidth={2}
                          dot={false}
                        />
                      ))}
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>

              <div className="glass-card" style={{ padding: '1.5rem' }}>
                <h3>Forecast RMSE</h3>
                <div style={{ height: '300px' }}>
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={citySnapshots}>
                      <CartesianGrid strokeDasharray="3 3" opacity={0.2} />
                      <XAxis dataKey="cityName" opacity={0.5} />
                      <YAxis opacity={0.5} />
                      <Tooltip contentStyle={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }} />
                      <Legend />
                      <Bar dataKey="forecastRmse" name="Model RMSE" fill="#8b5cf6" />
                      <Bar dataKey="baselineRmse" name="Baseline RMSE" fill="#64748b" />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>

              <div className="glass-card" style={{ padding: '1.5rem' }}>
                <h3>Enforcement Actions</h3>
                <div style={{ height: '300px' }}>
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={citySnapshots}>
                      <CartesianGrid strokeDasharray="3 3" opacity={0.2} />
                      <XAxis dataKey="cityName" opacity={0.5} />
                      <YAxis opacity={0.5} />
                      <Tooltip contentStyle={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }} />
                      <Legend />
                      <Bar dataKey="resolvedEnforcementCount" name="Actioned/Resolved" fill="#22c55e" />
                      <Bar dataKey="openEnforcementCount" name="Pending/Open" fill="#f43f5e" />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>

              <div className="glass-card" style={{ padding: '1.5rem' }}>
                <h3>City Metadata</h3>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                  {citySnapshots.map(snap => (
                    <div key={snap.cityId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingBottom: '0.5rem', borderBottom: '1px solid var(--color-border)' }}>
                      <div>
                        <strong>{snap.cityName}</strong>
                        {snap.isSynthetic && <span className="status-badge" style={{ marginLeft: '0.5rem', background: '#3b82f6' }}>Synthetic Data</span>}
                      </div>
                      <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                        <span>Avg AQI: {snap.averageAqi30d}</span>
                        <button 
                          className="btn btn--secondary" 
                          onClick={() => dispatch(setSelectedCity(snap.cityId))}
                          disabled={snap.cityId === selectedCity}
                        >
                          {snap.cityId === selectedCity ? 'Current' : 'Switch To'}
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
            {citySnapshots.length === 0 && <p>No multi-city snapshots available.</p>}
          </div>
        )}

        {activeTab === "health" && (
          <div className="gov-dashboard__health">
            <h2>System Health & Observability</h2>
            <p style={{ opacity: 0.8, marginBottom: '2rem' }}>Real-time metrics for AI components and scheduled jobs.</p>
            
            {metrics ? (
              <div className="metrics-grid" style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))' }}>
                <div className="glass-card" style={{ padding: '1.5rem' }}>
                  <h3>XGBoost Forecast</h3>
                  <div style={{ marginTop: '1rem', fontSize: '1.1rem' }}>
                    <p><strong>RMSE (AQI):</strong> {forecastMetrics ? forecastMetrics.rmse_aqi.toFixed(2) : (metrics.xgBoostRmse || 0).toFixed(2)}</p>
                    {forecastMetrics && (
                      <>
                        <p><strong>Baseline RMSE (Persistence):</strong> {forecastMetrics.baseline_rmse_aqi.toFixed(2)}</p>
                        <p><strong>Model Improvement %:</strong> <span style={{ color: 'var(--color-good)' }}>+{forecastMetrics.improvement_pct_aqi.toFixed(2)}%</span></p>
                      </>
                    )}
                    <p><strong>Last Success:</strong> {metrics.lastForecastJobSuccess ? new Date(metrics.lastForecastJobSuccess).toLocaleString() : 'N/A'}</p>
                    <p><strong>Failure Count:</strong> <span style={{ color: metrics.forecastJobFailureCount > 0 ? 'var(--color-severe)' : 'inherit' }}>{metrics.forecastJobFailureCount}</span></p>
                  </div>
                </div>
                
                <div className="glass-card" style={{ padding: '1.5rem' }}>
                  <h3>Gemini API</h3>
                  <div style={{ marginTop: '1rem', fontSize: '1.1rem' }}>
                    <p><strong>Avg Latency:</strong> {metrics.geminiAvgLatencyMs} ms</p>
                    <p><strong>P95 Latency:</strong> {metrics.geminiP95LatencyMs} ms</p>
                    <p><strong>Total Calls:</strong> {metrics.geminiTotalCalls}</p>
                    <p><strong>Fallback Rate:</strong> <span style={{ color: metrics.geminiFallbackPercentage > 10 ? 'var(--color-severe)' : 'inherit' }}>{metrics.geminiFallbackPercentage.toFixed(1)}%</span></p>
                  </div>
                </div>

                {advisoryCoverage && (
                  <div className="glass-card" style={{ padding: '1.5rem' }}>
                    <h3>Advisory Reach (System 5)</h3>
                    <div style={{ marginTop: '1rem', fontSize: '1.1rem' }}>
                      <p><strong>Total Notifications:</strong> {advisoryCoverage.totalSent}</p>
                      
                      <div style={{ marginTop: '1rem', borderTop: '1px solid var(--color-border)', paddingTop: '0.5rem' }}>
                        <p style={{ margin: '0.5rem 0' }}><strong>Language Breakdown</strong></p>
                        <div style={{ display: 'flex', gap: '1rem' }}>
                          <span>English: {advisoryCoverage.languageBreakdown.EN}</span>
                          <span>Hindi: {advisoryCoverage.languageBreakdown.HI}</span>
                        </div>
                      </div>

                      <div style={{ marginTop: '1rem', borderTop: '1px solid var(--color-border)', paddingTop: '0.5rem' }}>
                        <p style={{ margin: '0.5rem 0' }}><strong>Channel Breakdown</strong></p>
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
                          <span>In-App: {advisoryCoverage.channelBreakdown.IN_APP}</span>
                          <span>SMS: {advisoryCoverage.channelBreakdown.SMS}</span>
                          <span>Email: {advisoryCoverage.channelBreakdown.EMAIL}</span>
                          <span>Push: {advisoryCoverage.channelBreakdown.PUSH}</span>
                        </div>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            ) : (
              <p>Loading metrics...</p>
            )}

            {metrics && (
              <div className="evaluation-summary" style={{ marginTop: '3rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                  <h3>Evaluation Summary (Phase 14)</h3>
                  <button className="btn btn--primary" onClick={handleRunEvaluation}>
                    Run Evaluation
                  </button>
                </div>
                
                {!metrics.evaluationSummary ? (
                  <p>No evaluation runs found. Click "Run Evaluation" to generate synthetic ground truth and evaluate the system.</p>
                ) : (
                  <div className="metrics-grid" style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))' }}>
                    <div className="glass-card" style={{ padding: '1.5rem' }}>
                      <h4 style={{ color: 'var(--color-primary)', marginTop: 0 }}>Attribution Accuracy</h4>
                      <p><strong>Precision:</strong> {(metrics.evaluationSummary.attribution.precision * 100).toFixed(1)}%</p>
                      <p><strong>Recall:</strong> {(metrics.evaluationSummary.attribution.recall * 100).toFixed(1)}%</p>
                      <p><strong>F1 Score:</strong> {(metrics.evaluationSummary.attribution.f1 * 100).toFixed(1)}%</p>
                      <p><strong>Accuracy:</strong> {(metrics.evaluationSummary.attribution.accuracy * 100).toFixed(1)}%</p>
                    </div>

                    <div className="glass-card" style={{ padding: '1.5rem' }}>
                      <h4 style={{ color: 'var(--color-primary)', marginTop: 0 }}>Forecast Quality</h4>
                      <p><strong>Model RMSE:</strong> {metrics.evaluationSummary.forecast.modelRmse.toFixed(2)}</p>
                      <p><strong>Persistence RMSE:</strong> {metrics.evaluationSummary.forecast.persistenceRmse.toFixed(2)}</p>
                      <p><strong>Improvement:</strong> <span style={{ color: 'var(--color-good)' }}>+{metrics.evaluationSummary.forecast.improvementPct.toFixed(1)}%</span></p>
                    </div>

                    <div className="glass-card" style={{ padding: '1.5rem' }}>
                      <h4 style={{ color: 'var(--color-primary)', marginTop: 0 }}>Enforcement Intelligence</h4>
                      <p><strong>Avg Quality Score:</strong> {metrics.evaluationSummary.enforcement.averageQualityScore.toFixed(1)} / 100</p>
                      <p><strong>Passed Actions:</strong> {metrics.evaluationSummary.enforcement.passedActionCount}</p>
                      <p><strong>Failed Actions:</strong> {metrics.evaluationSummary.enforcement.failedActionCount}</p>
                    </div>

                    <div className="glass-card" style={{ padding: '1.5rem' }}>
                      <h4 style={{ color: 'var(--color-primary)', marginTop: 0 }}>Citizen Advisory</h4>
                      <p><strong>Relevance Rate:</strong> {(metrics.evaluationSummary.advisory.relevanceRate * 100).toFixed(1)}%</p>
                      <p><strong>Language Coverage:</strong> {(metrics.evaluationSummary.advisory.languageCoveragePercent * 100).toFixed(1)}%</p>
                      <p><strong>Total Advisories:</strong> {metrics.evaluationSummary.advisory.totalAdvisories}</p>
                    </div>

                    <div className="glass-card" style={{ padding: '1.5rem' }}>
                      <h4 style={{ color: 'var(--color-primary)', marginTop: 0 }}>System Latency</h4>
                      <p><strong>Avg Signal-to-Intervention:</strong> {metrics.evaluationSummary.responseTime.averageMs} ms</p>
                      <p><strong>Worst Case:</strong> {metrics.evaluationSummary.responseTime.worstCaseMs} ms</p>
                      <p><strong>Best Case:</strong> {metrics.evaluationSummary.responseTime.bestCaseMs} ms</p>
                      <p style={{ marginTop: '0.5rem', fontSize: '0.85rem', color: 'var(--color-text-muted)' }}>
                        Data Source: {metrics.evaluationSummary.dataSource}
                      </p>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {activeTab === "judge" && (
          <div className="gov-dashboard__judge">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2rem' }}>
              <div>
                <h2>Judge Readiness Report</h2>
                <p style={{ opacity: 0.8, margin: 0 }}>Executive summary of AI performance, scalability, and business impact.</p>
              </div>
              <span className="status-badge status-online" style={{ fontSize: '1.2rem', padding: '0.5rem 1rem' }}>All Systems Go</span>
            </div>

            {judgeReport ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
                {/* AI Performance */}
                <div className="glass-card" style={{ padding: '2rem', borderLeft: '6px solid var(--color-primary)' }}>
                  <h3 style={{ margin: '0 0 1.5rem 0', color: 'var(--color-primary)' }}>1. AI Performance Metrics</h3>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '1.5rem' }}>
                    <div>
                      <h4 style={{ margin: '0 0 0.5rem 0', opacity: 0.9 }}>Attribution (System 1)</h4>
                      <p style={{ fontSize: '1.5rem', fontWeight: 'bold', margin: 0 }}>{judgeReport.aiPerformance.attributionAccuracy}</p>
                    </div>
                    <div>
                      <h4 style={{ margin: '0 0 0.5rem 0', opacity: 0.9 }}>Forecasting (System 2)</h4>
                      <p style={{ margin: '0 0 0.25rem 0' }}>RMSE vs Baseline: <strong style={{ color: 'var(--color-good)' }}>-{judgeReport.aiPerformance.forecastMetrics.improvement_pct_aqi.toFixed(1)}% Error</strong></p>
                      <p style={{ margin: 0 }}>Absolute RMSE: <strong>{judgeReport.aiPerformance.forecastMetrics.rmse_aqi.toFixed(1)} AQI</strong></p>
                    </div>
                    <div>
                      <h4 style={{ margin: '0 0 0.5rem 0', opacity: 0.9 }}>Enforcement Intelligence (System 3)</h4>
                      <p style={{ margin: '0 0 0.25rem 0' }}>High Confidence Actions: <strong>{judgeReport.aiPerformance.enforcementQuality.highConfidencePercentage.toFixed(1)}%</strong></p>
                      <p style={{ margin: 0 }}>Total Generated: <strong>{judgeReport.aiPerformance.enforcementQuality.totalGenerated}</strong></p>
                    </div>
                  </div>
                </div>

                {/* Citizen Impact */}
                <div className="glass-card" style={{ padding: '2rem', borderLeft: '6px solid #e93f33' }}>
                  <h3 style={{ margin: '0 0 1.5rem 0', color: '#e93f33' }}>2. Public Health Reach (System 5)</h3>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '1.5rem' }}>
                    <div>
                      <h4 style={{ margin: '0 0 0.5rem 0', opacity: 0.9 }}>Total Dispatched</h4>
                      <p style={{ fontSize: '1.5rem', fontWeight: 'bold', margin: 0 }}>{judgeReport.advisoryReach.totalPersonalizedAdvisories}</p>
                    </div>
                    <div>
                      <h4 style={{ margin: '0 0 0.5rem 0', opacity: 0.9 }}>Language Equity</h4>
                      <p style={{ margin: '0 0 0.25rem 0' }}>English: <strong>{judgeReport.advisoryReach.languageCoverage.EN}</strong></p>
                      <p style={{ margin: 0 }}>Hindi: <strong style={{ color: 'var(--color-good)' }}>{judgeReport.advisoryReach.languageCoverage.HI}</strong></p>
                    </div>
                    <div>
                      <h4 style={{ margin: '0 0 0.5rem 0', opacity: 0.9 }}>Vulnerability</h4>
                      <p style={{ margin: 0, lineHeight: 1.4 }}>{judgeReport.advisoryReach.vulnerabilityPersonalization}</p>
                    </div>
                  </div>
                </div>

                {/* Architecture & Scalability */}
                <div className="glass-card" style={{ padding: '2rem', borderLeft: '6px solid #f29c33' }}>
                  <h3 style={{ margin: '0 0 1.5rem 0', color: '#f29c33' }}>3. Technical Architecture & Scalability</h3>
                  <ul style={{ margin: 0, paddingLeft: '1.5rem', display: 'flex', flexDirection: 'column', gap: '0.75rem', fontSize: '1.1rem' }}>
                    {judgeReport.scalabilityNotes.map((note, idx) => (
                      <li key={idx}>{note}</li>
                    ))}
                  </ul>
                </div>

                {/* Business Impact */}
                <div className="glass-card" style={{ padding: '2rem', borderLeft: '6px solid #55a84f' }}>
                  <h3 style={{ margin: '0 0 1.5rem 0', color: '#55a84f' }}>4. Real-World Business Impact</h3>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '1.5rem' }}>
                    <div style={{ background: 'rgba(255,255,255,0.05)', padding: '1rem', borderRadius: '4px' }}>
                      <h4 style={{ margin: '0 0 0.5rem 0' }}>Faster Response</h4>
                      <p style={{ margin: 0, lineHeight: 1.4 }}>{judgeReport.businessImpact.fasterEnforcement}</p>
                    </div>
                    <div style={{ background: 'rgba(255,255,255,0.05)', padding: '1rem', borderRadius: '4px' }}>
                      <h4 style={{ margin: '0 0 0.5rem 0' }}>Reduced Exposure</h4>
                      <p style={{ margin: 0, lineHeight: 1.4 }}>{judgeReport.businessImpact.expectedReducedExposure}</p>
                    </div>
                    <div style={{ background: 'rgba(255,255,255,0.05)', padding: '1rem', borderRadius: '4px' }}>
                      <h4 style={{ margin: '0 0 0.5rem 0' }}>Equitable Reach</h4>
                      <p style={{ margin: 0, lineHeight: 1.4 }}>{judgeReport.businessImpact.publicHealthReach}</p>
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <p>Aggregating report metrics...</p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
