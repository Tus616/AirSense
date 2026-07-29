# Final Acceptance

Final local acceptance completed on 2026-07-29:

- Full AI tests: `python -m pytest forecasting/tests -v` passed, 25 tests.
- Backend tests: `mvnw test` passed, 157 tests.
- Backend package: `mvnw -DskipTests package` passed.
- Frontend production build: `npm run build` passed.

Final release acceptance also requires:

- Clean reviewed git status after the final commit.
- Final commit pushed to `origin/main`.
- AI Render, backend Render, and Vercel verified after they deploy the final commit.
- Browser verification for signup/login, city search, forecast, attribution, geospatial, enforcement, advisory, explainability, and Copilot.
