Chronos is lazy-loaded and may degrade on low-memory CPU deployments. When it cannot load, Open-Meteo provider forecast and persistence fallback keep the product usable.

Open-Meteo provider fallback uses `US_AQI`; it is not converted into India NAQI.
