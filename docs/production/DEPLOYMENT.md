# Deployment

Production services:

- FastAPI AI service on Render.
- Spring Boot backend on Render.
- React/Vite frontend on Vercel.

Deploy the same Git commit to all three services. After deployment, verify AI `/health`, `/ready`, `/internal/forecast/status`, provider-only forecast prediction, backend auth, backend decision intelligence, and frontend dashboard rendering.

For Chronos-enabled deployments, install `ai-service/requirements-chronos.txt` in addition to the base AI requirements and set `CHRONOS_ENABLED=true`. Low-memory deployments should leave Chronos disabled.
