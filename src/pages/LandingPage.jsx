import { Link } from "react-router-dom";
import heroCity from "../assets/urban-air-smart-city-hero.png";

const navLinks = [
  ["Overview", "#overview"],
  ["Forecast", "#forecast"],
  ["Maps", "#maps"],
  ["Source Analysis", "#source-analysis"],
  ["Enforcement", "#enforcement"],
  ["Health Advisory", "#health-advisory"],
];

const dashboardMenu = ["Overview", "Forecast", "Maps", "Analytics", "Enforcement", "Health", "Advisory", "History", "Alerts", "Settings"];

const capabilities = [
  ["AQ", "Live AQI", "CPCB-backed air quality readings with station context and status visibility."],
  ["FC", "Hyperlocal Forecast", "24h, 48h, and 72h outlooks with confidence and fallback transparency."],
  ["SA", "Source Attribution", "Evidence-weighted estimates for traffic, dust, industry, biomass, and unknowns."],
  ["MP", "Geospatial", "Hotspots, risk zones, corridors, and field layers for fast spatial decisions."],
  ["EN", "Enforcement", "Operational recommendations that help teams prioritize field action."],
  ["HA", "Health Advisory", "Readable guidance for citizens, schools, vulnerable groups, and commuters."],
  ["BR", "Broadcast & Alerts", "Clear citizen communication across app, web, SMS, and public channels."],
  ["RB", "Secure Access", "Role-based entry separates public awareness from control-room workflows."],
];

const operatorWidgets = [
  ["Current AQI", "168", "CPCB CAAQMS"],
  ["Live Forecast", "72h", "Trend risk"],
  ["Source Mix", "Traffic 33%", "Evidence weighted"],
  ["Decision Queue", "7", "Open actions"],
  ["Collector Status", "Live", "CPCB sync"],
  ["Broadcast", "Ready", "Public alert"],
];

const operationsPreviewCards = [
  ["Current AQI", "168", "Very Poor trend"],
  ["City Summary", "34 stations", "16 live signals"],
  ["Live Forecast", "+18 AQI", "48h modeled risk"],
  ["Source Attribution", "Traffic 33%", "Dust 21%"],
  ["AQI Map / Hotspots", "12 layers", "5 active zones"],
  ["Hot Pollutants", "PM2.5", "NO2 watch"],
  ["Collector / Model", "Live", "Fallback visible"],
  ["Alerts Center", "4 drafts", "2 urgent"],
  ["Enforcement", "7 actions", "3 high priority"],
  ["Decision Copilot", "Ready", "Context-aware"],
];

const citizenPreviewCards = [
  ["Current AQI", "68", "Moderate"],
  ["Health Advisory", "Normal", "Sensitive groups watch"],
  ["Air Quality Map", "Nearby", "Local station view"],
  ["Public Alerts", "2", "Outdoor guidance"],
];

const flowSteps = [
  ["01", "Analyze & Decide", "In Dashboard"],
  ["02", "Notify & Reach", "SMS, App, Web, Email, IVRS, Social"],
  ["03", "Guided Actions", "Schools, traffic, advisories, field teams"],
  ["04", "Field Actions Update", "Track interventions and outcomes"],
];

function MiniIcon({ children, tone = "blue" }) {
  return <span className={`landing-icon landing-icon--${tone}`}>{children}</span>;
}

function LandingNavbar() {
  return (
    <header className="landing-nav">
      <Link to="/" className="landing-brand" aria-label="Urban Air Quality Intelligence home">
        <MiniIcon tone="green">AQ</MiniIcon>
        <span>Urban Air Quality Intelligence</span>
      </Link>
      <nav className="landing-nav__links" aria-label="Landing page sections">
        {navLinks.map(([label, href]) => (
          <a key={label} href={href}>{label}</a>
        ))}
      </nav>
      <div className="landing-nav__actions">
        <Link className="landing-button landing-button--ghost-green" to="/login">Citizen Login</Link>
        <Link className="landing-button landing-button--ghost-blue" to="/login">Team Login</Link>
        <Link className="landing-button landing-button--primary" to="/login">Open Dashboard</Link>
      </div>
    </header>
  );
}

function DashboardHeroPreview() {
  return (
    <div className="hero-preview" aria-label="Operations dashboard preview">
      <div className="hero-preview__sidebar">
        <strong>Command</strong>
        {dashboardMenu.map((item, index) => (
          <span key={item} className={index === 0 ? "is-active" : ""}>{item}</span>
        ))}
      </div>
      <div className="hero-preview__main">
        <div className="hero-preview__topline">
          <div>
            <small>Operations Dashboard</small>
            <b>Delhi NCR Air Command</b>
          </div>
          <span>Live</span>
        </div>
        <div className="hero-preview__grid">
          {operatorWidgets.map(([title, value, meta]) => (
            <article key={title}>
              <small>{title}</small>
              <b>{value}</b>
              <span>{meta}</span>
            </article>
          ))}
        </div>
        <div className="hero-preview__chart" aria-hidden="true">
          <span />
          <span />
          <span />
          <span />
          <i />
        </div>
      </div>
    </div>
  );
}

function HeroSection() {
  return (
    <section className="landing-hero" id="overview">
      <div className="landing-hero__copy">
        <span className="landing-badge">Trusted by city governments & agencies</span>
        <h1>Evidence-Driven Air Quality Intelligence for Smarter City Action</h1>
        <p>
          Live CPCB AQI, forecasting, source attribution, enforcement intelligence, health advisories,
          citizen communication, and decision support in one role-aware civic platform.
        </p>
        <div className="landing-hero__cta">
          <Link className="landing-access-card landing-access-card--citizen" to="/login">
            <MiniIcon tone="green">CV</MiniIcon>
            <span><b>Public Overview</b><small>For Citizens</small></span>
          </Link>
          <Link className="landing-access-card landing-access-card--team" to="/login">
            <MiniIcon tone="blue">TD</MiniIcon>
            <span><b>Team Dashboard</b><small>For Operators</small></span>
          </Link>
        </div>
        <div className="landing-trust-row">
          {["Live CPCB Integration", "24/7 AQI & Forecasting", "Evidence-Backed Decisions", "Secure & Role-Based"].map((item) => (
            <span key={item}>{item}</span>
          ))}
        </div>
      </div>
      <div className="landing-hero__visual">
        <img src={heroCity} alt="Clean smart city skyline with environmental sensing and green transit" />
        <DashboardHeroPreview />
      </div>
    </section>
  );
}

function RoleBasedAccessSection() {
  return (
    <section className="landing-section" id="health-advisory">
      <div className="landing-section__header">
        <span>Role-Based Access</span>
        <h2>Built for Every Stakeholder.</h2>
        <p>Citizens receive clear public guidance. Operations teams get the full intelligence workspace.</p>
      </div>
      <div className="role-grid">
        <article className="role-card role-card--citizen">
          <MiniIcon tone="green">CV</MiniIcon>
          <h3>Citizen View</h3>
          <p>Simplified. Clear. Actionable.</p>
          <div className="role-mini role-mini--citizen">
            <b>Current AQI 68</b>
            <span>Health advisory: normal activity</span>
            <span>Public alert: moderate precautions</span>
            <span>Outdoor guidance: stay informed</span>
          </div>
          <footer>Designed for awareness. Built for everyday decisions.</footer>
        </article>
        <article className="role-card role-card--team">
          <MiniIcon tone="blue">OC</MiniIcon>
          <h3>Operations / Control Team</h3>
          <p>Advanced. Actionable. Impactful.</p>
          <div className="role-mini role-mini--team">
            <span>Forecast risk: rising</span>
            <span>Source analysis: traffic dominant</span>
            <span>Enforcement: 7 actions queued</span>
            <span>Alerts: broadcast draft ready</span>
          </div>
          <ul>
            <li>Full intelligence dashboard</li>
            <li>Source analysis & analytics</li>
            <li>Enforcement & alerts</li>
            <li>Forecasts & early warnings</li>
          </ul>
          <footer>Built for teams who monitor, analyze & act every day.</footer>
        </article>
      </div>
    </section>
  );
}

function DashboardPreviewSection() {
  return (
    <section className="landing-preview-section" id="forecast">
      <span className="landing-anchor" id="maps" aria-hidden="true" />
      <div className="landing-section__header">
        <span>Experience Preview</span>
        <h2>One Platform. Two Clean Entry Points.</h2>
        <p>Compare the operational command view with the simplified public experience.</p>
      </div>
      <div className="experience-preview">
        <article className="ops-preview">
          <header>
            <span>Operations Dashboard Preview</span>
            <h3>For Control Teams</h3>
          </header>
          <div className="ops-preview__grid">
            {operationsPreviewCards.map(([title, value, meta]) => (
              <div key={title} className="preview-tile">
                <small>{title}</small>
                <b>{value}</b>
                <span>{meta}</span>
              </div>
            ))}
          </div>
        </article>
        <article className="public-preview">
          <header>
            <span>Public Overview Preview</span>
            <h3>For Citizens</h3>
          </header>
          <div className="public-preview__phone">
            {citizenPreviewCards.map(([title, value, meta]) => (
              <div key={title}>
                <small>{title}</small>
                <b>{value}</b>
                <span>{meta}</span>
              </div>
            ))}
            <strong>Stay Informed. Stay Safe.</strong>
          </div>
        </article>
      </div>
    </section>
  );
}

function FeatureCapabilitiesSection() {
  return (
    <section className="landing-section" id="source-analysis">
      <div className="landing-section__header">
        <span>Platform Capabilities</span>
        <h2>Powerful Capabilities for Cleaner Air & Smarter Cities</h2>
      </div>
      <div className="capability-grid">
        {capabilities.map(([icon, title, description], index) => (
          <article key={title} className="capability-card">
            <MiniIcon tone={index % 3 === 0 ? "green" : index % 3 === 1 ? "blue" : "orange"}>{icon}</MiniIcon>
            <h3>{title}</h3>
            <p>{description}</p>
          </article>
        ))}
      </div>
    </section>
  );
}

function BroadcastManagementSection() {
  return (
    <section className="broadcast-section" id="enforcement">
      <div className="landing-section__header">
        <span>Team To Citizen Communication</span>
        <h2>Broadcast & Alert Management</h2>
        <p>From control-room intelligence to clear public action in minutes.</p>
      </div>
      <div className="broadcast-flow">
        {flowSteps.map(([number, title, detail]) => (
          <article key={number}>
            <b>{number}</b>
            <h3>{title}</h3>
            <p>{detail}</p>
          </article>
        ))}
      </div>
      <strong className="broadcast-strip">Reach everyone, everywhere. Communicate clearly. Act together.</strong>
    </section>
  );
}

function LandingFooter() {
  const columns = {
    Platform: ["Live AQI", "Forecast", "Maps", "Source Analysis"],
    Solutions: ["Citizen Awareness", "Control Room", "Enforcement", "Public Health"],
    Resources: ["Documentation", "Status", "Data Quality", "Replay"],
    Company: ["About", "Security", "Governance", "Contact"],
    "Get in Touch": ["support@urban-aqi.example", "+91 City Control Desk", "India"],
  };

  return (
    <footer className="landing-footer">
      <div className="landing-footer__brand">
        <Link to="/" className="landing-brand landing-brand--footer">
          <MiniIcon tone="green">AQ</MiniIcon>
          <span>Urban Air Quality Intelligence</span>
        </Link>
        <p>Smarter Insights. Cleaner Cities. Healthier Lives.</p>
        <small>(c) 2026 Urban Air Quality Intelligence. All rights reserved.</small>
      </div>
      <div className="landing-footer__links">
        {Object.entries(columns).map(([title, links]) => (
          <div key={title}>
            <h3>{title}</h3>
            {links.map((link) => <a key={link} href="#overview">{link}</a>)}
          </div>
        ))}
      </div>
      <div className="landing-footer__trust">
        {["Cities Monitored", "Live CPCB Integration", "24/7/365 Monitoring", "Evidence-Backed Decisions", "99.9% Data Uptime", "ISO-ready Controls"].map((item) => (
          <span key={item}>{item}</span>
        ))}
        <div className="landing-footer__social">
          <a href="#overview" aria-label="LinkedIn">in</a>
          <a href="#overview" aria-label="Website">web</a>
          <a href="#overview" aria-label="Email">@</a>
        </div>
      </div>
    </footer>
  );
}

export default function LandingPage() {
  return (
    <div className="landing-page">
      <LandingNavbar />
      <main>
        <HeroSection />
        <RoleBasedAccessSection />
        <DashboardPreviewSection />
        <FeatureCapabilitiesSection />
        <BroadcastManagementSection />
      </main>
      <LandingFooter />
    </div>
  );
}
