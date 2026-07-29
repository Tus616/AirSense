# Final Test Results

Final local verification run on 2026-07-29:

- AI service: `python -m pytest forecasting/tests -v` passed, 25 tests.
- Backend: `mvnw test` passed, 157 tests.
- Backend package: `mvnw -DskipTests package` passed and produced `backend/target/api-0.0.1-SNAPSHOT.jar`.
- Frontend: `npm run build` passed and produced `dist/assets/index-Cle0cIeI.js`.

Known non-blocking warnings:

- AI tests emit pandas fragmentation warnings in feature construction tests.
- AI endpoint tests emit a Python `datetime.utcnow()` deprecation warning.
- Backend tests emit Mockito dynamic-agent warnings on the local JDK.
- Frontend build warns that the main minified chunk is larger than 500 kB.
