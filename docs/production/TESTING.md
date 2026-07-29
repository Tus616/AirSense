# Testing

Run:

```powershell
cd ai-service
.\venv\Scripts\python.exe -m pytest forecasting\tests -v

cd ..\backend
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package

cd ..
& 'C:\Program Files\nodejs\npm.cmd' run build
```

Do not delete tests to pass. Fix stale temp permission issues by removing only the stale temp directory, then rerun.
