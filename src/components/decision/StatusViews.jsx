export function LoadingState() {
  return (
    <div className="decision-state">
      <div className="decision-spinner" aria-hidden="true" />
      <h2>Loading decision intelligence</h2>
      <p>Gathering forecast, attribution, enforcement, advisory, and evidence signals.</p>
    </div>
  );
}

export function RiskOverviewSkeleton() {
  return (
    <section className="decision-overview" aria-label="Loading current AQI and risk">
      <article className="decision-aqi-card decision-skeleton-card">
        <span className="decision-eyebrow">Current AQI</span>
        <div className="decision-skeleton decision-skeleton--aqi" />
        <div className="decision-skeleton" />
        <div className="decision-skeleton decision-skeleton--short" />
      </article>
      <article className="decision-risk-card decision-skeleton-card">
        <span className="decision-eyebrow">Overall Risk</span>
        <div className="decision-skeleton decision-skeleton--title" />
        <div className="decision-skeleton" />
        <div className="decision-skeleton decision-skeleton--short" />
      </article>
      <article className="decision-summary-panel decision-skeleton-card">
        <div className="decision-skeleton decision-skeleton--title" />
        <div className="decision-skeleton" />
        <div className="decision-skeleton" />
        <div className="decision-skeleton decision-skeleton--short" />
      </article>
    </section>
  );
}

export function PanelSkeleton({ eyebrow, title, lines = 3 }) {
  return (
    <section className="decision-panel decision-skeleton-card" aria-label={title}>
      <div className="decision-panel__header">
        <div>
          <span className="decision-eyebrow">{eyebrow}</span>
          <h2>{title}</h2>
        </div>
      </div>
      {Array.from({ length: lines }).map((_, index) => (
        <div
          className={`decision-skeleton${index === lines - 1 ? " decision-skeleton--short" : ""}`}
          key={index}
        />
      ))}
    </section>
  );
}

export function ErrorState({ message, onRetry }) {
  return (
    <div className="decision-state decision-state--error">
      <h2>Decision intelligence unavailable</h2>
      <p>{message || "The request could not be completed."}</p>
      <button className="decision-button" type="button" onClick={onRetry}>
        Retry
      </button>
    </div>
  );
}

export function EmptyState({ onRetry }) {
  return (
    <div className="decision-state">
      <h2>No decision response returned</h2>
      <p>The platform did not return a usable decision object for this city.</p>
      <button className="decision-button" type="button" onClick={onRetry}>
        Retry
      </button>
    </div>
  );
}
