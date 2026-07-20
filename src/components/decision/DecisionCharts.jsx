import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { asArray, asNumber, getForecastPoints, labelize } from "./decisionUtils";

const COLORS = ["#227093", "#d99b18", "#a93434", "#218c74", "#596275", "#b35d2e", "#5f6caf"];

function pollutantData(decision) {
  const pollutants = decision?.environmentalSignals?.pollutants || {};
  return ["pm25", "pm10", "no2", "so2", "o3", "co"].map((key) => ({
    name: labelize(key),
    value: asNumber(pollutants[key]),
  })).filter((item) => item.value > 0);
}

function trendData(timeline) {
  return asArray(timeline?.frames).map((frame) => ({
    name: frame.label || frame.frameType || "Frame",
    aqi: asNumber(frame.aqi),
  })).filter((item) => item.aqi > 0);
}

function forecastData(forecastResult) {
  return getForecastPoints(forecastResult)
    .map((point) => ({
      name: point.key,
      predicted: asNumber(point.predictedAqi),
      lower: asNumber(point.lowerBound),
      upper: asNumber(point.upperBound),
      baseline: asNumber(forecastResult?.baseline?.[point.key]?.predictedAqi),
    }))
    .filter((item) => item.predicted > 0);
}

function sourceData(attribution) {
  return asArray(attribution?.sources)
    .map((source) => ({
      name: labelize(source.sourceType),
      value: asNumber(source.contributionPercent),
    }))
    .filter((item) => item.value > 0);
}

export default function DecisionCharts({ decision, timeline }) {
  const pollutants = pollutantData(decision);
  const trend = trendData(timeline);
  const forecast = forecastData(decision?.forecast);
  const sources = sourceData(decision?.attribution);

  return (
    <section className="decision-panel decision-charts">
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">Live Analytics</span>
          <h2>Environmental Signal Charts</h2>
        </div>
        <span className="decision-chip">{decision?.environmentalSignals?.aqiDataSource || "live providers"}</span>
      </div>

      <div className="decision-chart-grid">
        <ChartFrame title="Pollutant Breakdown" empty={pollutants.length === 0}>
          <ResponsiveContainer width="100%" height={190}>
            <BarChart data={pollutants}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="name" tickLine={false} axisLine={false} />
              <YAxis tickLine={false} axisLine={false} width={36} />
              <Tooltip />
              <Bar dataKey="value" radius={[4, 4, 0, 0]} fill="#227093" />
            </BarChart>
          </ResponsiveContainer>
        </ChartFrame>

        <ChartFrame title="AQI Trend" empty={trend.length === 0}>
          <ResponsiveContainer width="100%" height={190}>
            <LineChart data={trend}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="name" tickLine={false} axisLine={false} />
              <YAxis tickLine={false} axisLine={false} width={36} />
              <Tooltip />
              <Line type="monotone" dataKey="aqi" stroke="#a93434" strokeWidth={3} dot={{ r: 3 }} />
            </LineChart>
          </ResponsiveContainer>
        </ChartFrame>

        <ChartFrame title="24h / 48h / 72h Forecast" empty={forecast.length === 0}>
          <ResponsiveContainer width="100%" height={190}>
            <BarChart data={forecast}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="name" tickLine={false} axisLine={false} />
              <YAxis tickLine={false} axisLine={false} width={36} />
              <Tooltip />
              <Bar dataKey="lower" fill="#9ca3af" radius={[4, 4, 0, 0]} />
              <Bar dataKey="baseline" fill="#596275" radius={[4, 4, 0, 0]} />
              <Bar dataKey="predicted" fill="#d99b18" radius={[4, 4, 0, 0]} />
              <Bar dataKey="upper" fill="#a93434" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </ChartFrame>

        <ChartFrame title="Source Attribution" empty={sources.length === 0}>
          <ResponsiveContainer width="100%" height={190}>
            <PieChart>
              <Pie data={sources} dataKey="value" nameKey="name" innerRadius={46} outerRadius={72} paddingAngle={2}>
                {sources.map((entry, index) => (
                  <Cell key={entry.name} fill={COLORS[index % COLORS.length]} />
                ))}
              </Pie>
              <Tooltip />
            </PieChart>
          </ResponsiveContainer>
        </ChartFrame>
      </div>
    </section>
  );
}

function ChartFrame({ title, empty, children }) {
  return (
    <article className="decision-chart-frame">
      <h3>{title}</h3>
      {empty ? <div className="decision-empty-line">Live data unavailable for this chart.</div> : children}
    </article>
  );
}
