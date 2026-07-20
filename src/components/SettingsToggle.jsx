/**
 * Reusable toggle switch component
 * @param {{ label: string, description?: string, checked: boolean, onChange: () => void }} props
 */
export default function SettingsToggle({ label, description, checked, onChange }) {
  return (
    <div className="settings-toggle" onClick={onChange} role="button" tabIndex={0}>
      <div className="settings-toggle__text">
        <span className="settings-toggle__label">{label}</span>
        {description && <span className="settings-toggle__desc">{description}</span>}
      </div>
      <div className={`settings-toggle__switch ${checked ? "settings-toggle__switch--on" : ""}`}>
        <div className="settings-toggle__knob" />
      </div>
    </div>
  );
}
