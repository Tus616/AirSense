# Unavailable State Audit

Generated during the production reliability pass on 2026-07-29.

## Status contract

Decision responses expose `moduleStatuses` keyed by module. Each entry includes `status`, `dataOrigin`, `confidence`, `reason`, `limitations`, `missingInputs`, `generatedAt`, and `snapshotId`.

Allowed status values used by the backend are `AVAILABLE`, `DERIVED`, `FALLBACK`, `PARTIAL`, `UNAVAILABLE`, and `ERROR`.

Allowed data-origin values used by the backend are `OBSERVED_REAL_DATA`, `DERIVED_FROM_REAL_DATA`, `OPEN_METEO_PROVIDER_FORECAST`, `PERSISTENCE_FALLBACK`, `RULE_BASED_INFERENCE`, `USER_CONTEXT`, and `UNAVAILABLE`.

## Findings and fallback decisions

| Module or UI area | Previous unavailable state | Implemented behavior |
| --- | --- | --- |
| Current AQI | AQI cards could show generic unavailable/NA without a backend reason. | `moduleStatuses.currentAqi` reports observed real data when canonical AQI is valid, otherwise exact missing input and provider reason. Dashboard details now display status and reason. |
| Pollutants | Pollutant bars could show `NA` without distinguishing missing pollutant rows from missing AQI. | `moduleStatuses.pollutants` distinguishes observed pollutant values, AQI-only derived context, and true missing pollutants. No pollutant concentration is fabricated. |
| Forecast | Forecast cards inferred status only from per-point engine/fallback fields. | `moduleStatuses.forecast` reports valid forecast horizon availability and origin. Forecast UI displays that module status and reason. |
| Source attribution | Empty attribution lists rendered as generic unavailable. | Existing attribution engine keeps `UNKNOWN`/low-confidence share rather than over-assigning sources. Decision status marks attribution as `DERIVED`, `PARTIAL`, or `UNAVAILABLE` based on sources/confidence. |
| Hotspots | `AQI_HOTSPOTS` returned no features when exact admin/hotspot geometry was absent. | If real selected coordinates plus current/forecast AQI exist, backend returns one deterministic `selected_location_circle` point feature with `radiusMeters`, `featureKind=CIRCLE`, `DERIVED_FROM_REAL_DATA`, low confidence, and limitations. |
| Forecast geospatial layers | `FORECAST_GRID_24H/48H/72H` were empty/unavailable even when forecast AQI values existed. | Each valid forecast point returns a deterministic selected-location forecast risk circle with horizon, predicted AQI, radius, confidence, origin, and limitations. |
| Map circles | Point GeoJSON rendered as pixel circle markers only. | `GisDecisionMap.jsx` now renders any point feature with `radiusMeters` as a Leaflet meter-radius circle. |
| Enforcement | Empty action queues displayed as unavailable. | `moduleStatuses.enforcement` distinguishes rule-based actions from precise limited status when no recommendation crosses thresholds. UI shows the reason when the queue is empty. |
| Health advisory | Empty advisory groups displayed as no returned advisory. | Decision status marks advisory as rule-based available or precise limited status. Existing UI keeps non-fabricated empty state. |
| City summary | Missing city summary displayed as insufficient. | `moduleStatuses.citySummary` distinguishes multi-station derived summaries from local-only AQI. UI now reports: local location AQI is available, but city-wide summary requires multiple fresh same-standard stations. |
| Explainability | Summary can be partial while detailed endpoint is on demand. | `moduleStatuses.explainability` marks partial derived status and points to the dedicated endpoint behavior. |
| Copilot | Unsupported or under-evidenced answers can return unavailable. | `moduleStatuses.copilot` marks on-demand user-context status and documents that unsupported questions return precise limited status. |
| Geospatial exact source zones | Exact construction, industrial, sensitive, green, satellite geometries may be absent. | These remain layer-level `UNAVAILABLE` or `PARTIAL` with exact metadata reasons unless real OSM/imported/provider geometry exists. No synthetic facility/source polygons are generated. |

## Hotspot algorithm

1. Prefer imported or OpenStreetMap administrative boundary geometry.
2. If boundary geometry is missing but selected coordinates and current/forecast AQI are valid, create one deterministic circle centered on the selected coordinates.
3. Radius is `clamp(900 + AQI * 7.5, 1200, 4500)` meters.
4. Confidence is capped at `0.55` because the geometry is derived from real point evidence rather than an observed polygon.
5. Metadata declares `geometrySource=selected_location_circle`, `status=DERIVED`, and `dataOrigin=DERIVED_FROM_REAL_DATA`.

## Remaining genuine limited states

Exact construction sites, industrial zones, sensitive receptor points, green polygons, and satellite raster/vector geometries remain unavailable when the configured GeoJSON, OSM, or satellite providers return no real geometry. These are not replaced with synthetic polygons.

Historical replay remains unavailable for unsupported station/date pairs by design. The dashboard keeps that state explicit.
