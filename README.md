# VATM AeroSync

Multi-module data sync platform: ingest files from email or a folder, process them through a pipeline, store flight data in Oracle, and monitor via a REST API and Windows desktop UI.

| Module | Role |
|--------|------|
| `aerosync-ingest` | Watches incoming folder + IMAP email, publishes jobs to RabbitMQ |
| `aerosync-worker` | Processes files, validates data, writes to Oracle |
| `aerosync-api` | Dashboard REST API (stats, jobs, config, audit logs) |
| `aerosync-ui` | WinUI operator dashboard |
| `aerosync-common` | Shared entities, DTOs, repositories |

---

## Development install (Windows)

Use this guide to set up AeroSync on a **new dev machine**.

### 1. Prerequisites

Install the following before you start:

| Software | Version | Required for |
|----------|---------|--------------|
| **Windows** | 10/11 (64-bit) | All components |
| **Git** | Latest | Clone the repository |
| **Docker Desktop** | Latest | Local Oracle, RabbitMQ, Redis (dev only) |
| **Java JDK** | **21** | Backend services (Maven wrapper included) |
| **.NET SDK** | **8** | Desktop UI only (optional) |

After installing Docker Desktop, **start it** and wait until it reports running before launching AeroSync.

### 2. Clone the repository

```powershell
git clone <repository-url> VATM-AeroSync
cd VATM-AeroSync
```

You can also copy the project folder directly if Git is not used on that machine.

### 3. Create environment config

```powershell
copy .env.example .env
```

Edit `.env` with a text editor. For a basic local setup, the defaults work with Docker Compose. Change these only if needed:

| Setting | Default | Notes |
|---------|---------|-------|
| `SPRING_DATASOURCE_*` | `localhost:1521/XEPDB1` | Matches `docker-compose.yml` |
| `SPRING_RABBITMQ_*` | `localhost:5672` | guest / guest |
| `SPRING_DATA_REDIS_*` | `localhost:6379` | |
| `APP_FILE_PATHS_*` | `C:/vatm-storage/...` | Created automatically on first run |
| `APP_INGEST_SCHEDULER_FIXED_DELAY_MS` | `300000` (5 min) | Use `30000` for faster dev testing |
| `APP_EMAIL_*` | Empty password | Optional — see [Email ingest](#email-ingest-optional) |

> **Never commit `.env`** — it may contain passwords. Only `.env.example` belongs in Git.

### 4. Start the stack (one command)

From the repository root:

```powershell
.\run-aerosync.bat
```

On first run, if `.env` does not exist, the script copies `.env.example` and exits — edit `.env`, then run again.

The script will:

1. Create file storage folders (`incoming`, `processed`, `error`, `quarantine`)
2. Start Docker services: **Oracle XE**, **RabbitMQ**, **Redis**
3. Wait for all services to become healthy (first Oracle start can take **5–10+ minutes**)
4. Build Java apps with Maven
5. Open three PowerShell windows: **worker**, **ingest**, **api**

When finished, you should see:

| Endpoint | URL |
|----------|-----|
| API stats | http://localhost:8080/api/dashboard/stats |
| API jobs | http://localhost:8080/api/jobs |
| RabbitMQ UI | http://localhost:15672 (guest / guest) |

**Optional flags:**

```powershell
.\run-aerosync.bat -SkipBuild     # Skip Maven build (JARs already built)
.\run-aerosync.bat -SkipDocker    # Use existing Oracle/RabbitMQ/Redis elsewhere
```

**Stop infrastructure:**

```powershell
.\run-aerosync.bat stop
```

Close the worker / ingest / api PowerShell windows manually if they are still open.

### 5. Run the desktop UI (optional)

The UI is separate from `run-aerosync.bat`. Build and launch it after the API is running:

```powershell
dotnet build aerosync-ui\AeroSync.UI.csproj -p:Platform=x64
.\aerosync-ui\bin\x64\Debug\net8.0-windows10.0.19041.0\AeroSync.UI.exe
```

The UI connects to `http://localhost:8080` and auto-refreshes every 3 seconds.

### 6. Verify with a test file

Copy the sample CSV into the incoming folder:

```powershell
copy aerosync-worker\src\test\resources\samples\valid-flights.csv C:\vatm-storage\incoming\
```

Wait for the next ingest cycle (or set `APP_INGEST_SCHEDULER_FIXED_DELAY_MS=30000` in `.env` and restart). Then check:

- http://localhost:8080/api/dashboard/stats — `SUCCESS` count should increase
- UI dashboard — job appears in the sync list

### 7. Confirm data in Oracle (optional)

```powershell
docker exec -i aerosync-oracle sqlplus -s vatm_user/vatm_password@XEPDB1 <<< "SELECT COUNT(*) FROM flight_data; EXIT;"
```

Or use any Oracle client connected to `localhost:1521/XEPDB1` as `vatm_user`.

---

## Email ingest (optional)

To ingest attachments from a real mailbox:

1. Set credentials in `.env`:

   ```env
   APP_EMAIL_HOST=imap.gmail.com
   APP_EMAIL_USERNAME=you@gmail.com
   APP_EMAIL_PASSWORD=your-app-password
   APP_EMAIL_WHITELIST_SENDERS_0=you@gmail.com
   ```

2. Test IMAP connectivity:

   ```powershell
   .\scripts\test-imap.ps1
   ```

3. Restart ingest (close its PowerShell window and run `.\scripts\start-ingest.ps1`, or restart `.\run-aerosync.bat`).

For Gmail, use an [App Password](https://support.google.com/accounts/answer/185833), not your normal login password.

---

## Manual start (individual services)

If you prefer to run services yourself instead of `run-aerosync.bat`:

```powershell
# 1. Infrastructure
docker compose up -d

# 2. Build (from repo root)
.\aerosync-worker\mvnw.cmd -f pom.xml clean package -Dmaven.test.skip=true -pl aerosync-ingest,aerosync-worker,aerosync-api -am

# 3. Start each app (separate terminals)
.\scripts\start-worker.ps1
.\scripts\start-ingest.ps1
.\scripts\start-api.ps1
```

---

## Ports

Ensure these ports are free on the dev machine:

| Port | Service |
|------|---------|
| 1521 | Oracle |
| 5672 | RabbitMQ (AMQP) |
| 6379 | Redis |
| 8080 | AeroSync API |
| 15672 | RabbitMQ management UI |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `Docker not found` | Docker Desktop not installed or not running | Install/start Docker Desktop |
| Oracle timeout on first run | XE cold start is slow | Wait longer; run `docker compose logs oracle-db` |
| Maven build fails | Wrong Java version | Install **Java 21** (`java -version`) |
| `API unavailable` in UI | API not started or port blocked | Check http://localhost:8080/api/dashboard/stats |
| UI build fails | Missing .NET / Windows SDK | Install .NET 8 SDK |
| Email not ingesting | Missing password or sender not whitelisted | Fill `APP_EMAIL_*` in `.env`; run `.\scripts\test-imap.ps1` |
| File copied but no new job | Same file content (hash dedup) | Change file content or use a different file |
| SUCCESS stays at 1 | Duplicate content re-ingested | Expected — only unique file hashes create new jobs |

---

## Project layout

```
VATM-AeroSync/
├── aerosync-ingest/     # File + email ingest service
├── aerosync-worker/     # Processing pipeline
├── aerosync-api/        # REST API
├── aerosync-ui/         # WinUI dashboard
├── aerosync-common/     # Shared library
├── docker-compose.yml   # Dev infrastructure only
├── .env.example         # Config template
├── run-aerosync.bat     # One-shot dev startup
└── scripts/             # Helper scripts
```

---

## Dev vs production

| | Development | Production (customer) |
|---|-------------|----------------------|
| **Oracle** | Docker (`docker-compose.yml`) | Customer's Oracle instance |
| **RabbitMQ / Redis** | Docker | Installed or managed servers |
| **Apps** | JARs via `run-aerosync.bat` | JARs as Windows/Linux services |
| **UI** | `dotnet build` + run EXE | Installed desktop app |
| **Docker required?** | Yes (recommended) | No — connect to real infrastructure via `.env` |

Docker in this repo is a **local dev shortcut**. For customer deployment, install or connect to actual Oracle, RabbitMQ, and Redis, then configure `.env` with production hostnames and credentials.

---

## Quick reference

```powershell
# First-time setup
copy .env.example .env
# edit .env if needed
.\run-aerosync.bat

# Desktop UI
dotnet build aerosync-ui\AeroSync.UI.csproj -p:Platform=x64
.\aerosync-ui\bin\x64\Debug\net8.0-windows10.0.19041.0\AeroSync.UI.exe

# Test file ingest
copy aerosync-worker\src\test\resources\samples\valid-flights.csv C:\vatm-storage\incoming\

# Stop
.\run-aerosync.bat stop
```
