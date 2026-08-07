# VATM AeroSync

Multi-module data sync platform: ingest files from email or a folder, process them through a pipeline, store flight data in Oracle Database, and monitor via a REST API and Windows desktop UI.

| Module | Role |
|--------|------|
| `aerosync-ingest` | Watches incoming folder + IMAP email, publishes jobs to RabbitMQ |
| `aerosync-worker` | Processes files, validates data, writes to Oracle Database |
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
| **Java JDK** | **21** | Backend services (Maven wrapper included) |
| **.NET SDK** | **8** | Desktop UI only (optional) |
| **Oracle Database XE** | 21c | Database |
| **RabbitMQ** | 4.x | Message queue (requires Erlang OTP) |
| **Redis** | 7.x | Dedup, caching, distributed locks |

Make sure Oracle Database XE, RabbitMQ, and Redis are running as Windows services before launching AeroSync.

### 2. Clone the repository

```powershell
git clone <repository-url> VATM-AeroSync
cd VATM-AeroSync
```

You can also copy the project folder directly if Git is not used on that machine.

### 3. Create the database

Oracle XE creates the `XEPDB1` pluggable database during installation. Create the AeroSync schema user by connecting as SYSDBA and running the supplied setup script:

```powershell
cd <oracle-home>\bin
.\sqlplus.exe / as sysdba
@D:\path\to\VATM-AeroSync\scripts\setup-oracle.sql
```

The script prompts for the `VATM_USER` password. Put that same password in `.env`.

Initialize the AeroSync tables while connected as that schema user:

```powershell
.\sqlplus.exe vatm_user@//localhost:1521/XEPDB1 `
  @D:\path\to\VATM-AeroSync\scripts\init-aerosync-oracle.sql
```

The initialization script creates only AeroSync-owned tables, indexes, and constraints.
The legacy training/`FLIGHT_DATA` table and sequence are intentionally excluded. It can be run again safely: existing tables and
indexes are kept. After initialization, set
`SPRING_JPA_HIBERNATE_DDL_AUTO=validate` so application startup verifies the schema
without modifying it.

#### Use the existing ATFM schema

To keep AeroSync's tracking data in the existing ATFM schema, connect as the ATFM
schema user and run the same initialization script:

```powershell
sqlplus <ATFM_USER>@//172.29.187.90:1521/PDBORCL `
  @D:\path\to\VATM-AeroSync\scripts\init-aerosync-oracle.sql
```

Then configure all three services with the ATFM schema as their primary datasource:

```env
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//172.29.187.90:1521/PDBORCL
SPRING_DATASOURCE_USERNAME=<ATFM_USER>
SPRING_DATASOURCE_PASSWORD=<PASSWORD>
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

The script does not change `T_PERMMASTER_SC`, `T_PERMDETAIL_SC`, or other existing
ATFM tables. The connected user needs `CREATE TABLE`, `CREATE SEQUENCE`, and
tablespace quota for the first run.

### 4. Create environment config

```powershell
copy .env.example .env
```

Edit `.env` with a text editor. For a basic local setup, the defaults point to localhost. Change these only if needed:

| Setting | Default | Notes |
|---------|---------|-------|
| `SPRING_DATASOURCE_*` | `localhost:1521/XEPDB1` | Oracle XE connection |
| `SPRING_RABBITMQ_*` | `localhost:5672` | guest / guest |
| `SPRING_DATA_REDIS_*` | `localhost:6379` | |
| `APP_FILE_PATHS_*` | `D:/vatm-storage/...` | Created automatically on first run |
| `APP_INGEST_SCHEDULER_FIXED_DELAY_MS` | `300000` (5 min) | Use `30000` for faster dev testing |
| `APP_EMAIL_*` | Empty password | Optional — see [Email ingest](#email-ingest-optional) |

> **Never commit `.env`** — it may contain passwords. Only `.env.example` belongs in Git.

### 5. Start the stack (one command)

From the repository root:

```powershell
.\run-aerosync.bat
```

On first run, if `.env` does not exist, the script copies `.env.example` and exits — edit `.env`, then run again.

The script will:

1. Create file storage folders (`incoming`, `processed`, `error`, `quarantine`). Archived files are automatically partitioned below the last three as `yyyy/MM/dd`.
2. Verify Oracle XE, RabbitMQ, and Redis are running
3. Build Java apps with Maven
4. Open three PowerShell windows: **worker**, **ingest**, **api**

When finished, you should see:

| Endpoint | URL |
|----------|-----|
| API stats | http://localhost:8080/api/dashboard/stats |
| API jobs | http://localhost:8080/api/jobs |
| RabbitMQ UI | http://localhost:15672 (guest / guest) |

**Optional flags:**

```powershell
.\run-aerosync.bat -SkipBuild     # Skip Maven build (JARs already built)
```

**Stop apps:**

```powershell
.\run-aerosync.bat stop
```

### 6. Run the desktop UI (optional)

The UI is separate from `run-aerosync.bat`. Build and launch it after the API is running:

```powershell
dotnet build aerosync-ui\AeroSync.UI.csproj -p:Platform=x64
.\aerosync-ui\bin\x64\Debug\net8.0-windows10.0.19041.0\AeroSync.UI.exe
```

The UI connects to `http://localhost:8080` and auto-refreshes every 3 seconds.

### 7. Verify with a test file

Copy the sample CSV into the incoming folder:

```powershell
copy aerosync-worker\src\test\resources\samples\valid-flights.csv D:\vatm-storage\incoming\
```

Wait for the next ingest cycle (or set `APP_INGEST_SCHEDULER_FIXED_DELAY_MS=30000` in `.env` and restart). Then check:

- http://localhost:8080/api/dashboard/stats — `SUCCESS` count should increase
- UI dashboard — job appears in the sync list

### 8. Confirm data in Oracle Database (optional)

The legacy CSV/training `flight_data` writer is currently disabled. Permit
documents are verified in the ATFM master/detail tables instead; the old
`flight_data` query is intentionally not used.

---

## Email ingest (optional)

To ingest attachments from a real mailbox:

1. Set credentials in `.env`:

   ```env
   APP_EMAIL_HOST=imap.gmail.com
   APP_EMAIL_USERNAME=you@gmail.com
   APP_EMAIL_PASSWORD=your-app-password
   APP_EMAIL_FOLDER=INBOX
   APP_EMAIL_PROCESSED_FOLDER=AeroSync/Processed
   APP_EMAIL_ERROR_FOLDER=AeroSync/Error
   APP_EMAIL_OLDEST_MESSAGES_PER_CYCLE=5
   APP_EMAIL_BLACKLIST_SENDERS_0=you@gmail.com
   ```

2. Test IMAP connectivity:

   ```powershell
   .\scripts\test-imap.ps1
   ```

3. Restart ingest (close its PowerShell window and run `.\scripts\start-ingest.ps1`, or restart `.\run-aerosync.bat`).

Email envelopes are checked newest-first before attachment bodies are downloaded. The ingest service combines an IMAP received-today search with a bounded recent window (500 messages by default) and a small old-backlog window, so a large mailbox does not delay current mail. Completed IMAP UIDs and Message-IDs are skipped in Oracle, five slots per cycle are reserved for old backlog by default, and unsupported attachment types are recorded as skipped without creating worker jobs. Automatic moves to `AeroSync/Processed` or `AeroSync/Error` are disabled by default; enable them only after testing with `APP_EMAIL_ACKNOWLEDGEMENT_ENABLED=true`. Run `scripts/migrate-phase4-oracle.sql` once when upgrading an existing database.

### Gmail resend

The desktop **Resend email** screen can create a new Gmail message containing only attachments whose processing statuses were selected. Configure a Gmail account with an App Password in `.env`, then restart `aerosync-api`:

```env
APP_EMAIL_RESEND_ENABLED=true
APP_EMAIL_RESEND_SMTP_HOST=smtp.gmail.com
APP_EMAIL_RESEND_SMTP_PORT=587
APP_EMAIL_RESEND_USERNAME=your-resend-account@gmail.com
APP_EMAIL_RESEND_PASSWORD=your-gmail-app-password
APP_EMAIL_RESEND_RECIPIENT=mailbox-monitored-by-aerosync@vatm.vn
APP_EMAIL_RESEND_STARTTLS=true
```

Each resend receives a new `Message-ID`. It does not delete existing ATFM data. Normal ingest and SHA-256 duplicate checks still apply when the new email reaches the monitored mailbox.

### Scheduled permit Word import

The worker recognizes CAAV landing and overflight permits in both `.doc` and `.docx`
files. This includes issued and revised forms, English and Vietnamese headers,
IATA/ICAO airport variants, IATA/ICAO flight-number prefixes, and the recurring
aircraft and schedule layouts used by CAAV.

You do not need to import files one at a time. Copy a whole folder into the incoming
directory:

```powershell
Copy-Item -Path 'C:\source-permits\*.doc*' -Destination 'D:\vatm-storage\incoming'
```

The ingest service accepts up to 100 files per scan by default, so 50 documents are
discovered in one cycle and then processed independently. Set
`APP_INGEST_MAX_FILES_PER_CYCLE` if a larger batch is required. Each permit still
uses its own target transaction: one master row in `T_PERMMASTER_SC`, followed by
its schedule rows in `T_PERMDETAIL_SC` using the generated `PERM_ID`.

Format recognition is profile-based rather than automatic machine-learning
training. The legacy `flight_data`/training persistence path is intentionally
disabled and is not part of the active worker schema. The reusable profiles are
stored under `aerosync-worker/src/main/resources/permit-formats`, while airport
and aircraft normalization data is under `permit-reference`. New files with one
of the configured layouts are handled automatically; a genuinely new layout
requires another YAML profile or reference mapping.

Revision forms are parsed and validated, but are marked `REVISION_REVIEW` instead
of being written automatically. This prevents a revision from silently replacing
an existing ATFM permit. For legacy revisions such as `LD-06/A/S/2026VN/REV8`, the
worker uses only the new-schedule table and normalizes the permit identity as
`LD-06/A/S/2026`.

To regression-test a local directory of Word permits without importing anything:

```powershell
.\aerosync-worker\mvnw.cmd -f pom.xml test -pl aerosync-worker -am `
  '-Dtest=WordPermitCorpusRegressionTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dpermit.corpus.dir=C:\source-permits'
```

Configure the legacy target separately from AeroSync's own tracking database:

```env
APP_ATFM_DATASOURCE_URL=jdbc:oracle:thin:@//172.29.187.90:1521/PDBORCL
APP_ATFM_DATASOURCE_USERNAME=atfm
APP_ATFM_DATASOURCE_PASSWORD=your-password
APP_ATFM_WRITE_ENABLED=false
APP_ATFM_MANUAL_REVIEW_ENABLED=true
```

Writes are disabled by default. With the flag disabled, a valid permit is recorded as `DRY_RUN` and quarantined so it cannot be mistaken for a successful target insert. Run `scripts/migrate-phase5-oracle.sql` against the AeroSync tracking schema before enabling imports. After review, set `APP_ATFM_WRITE_ENABLED=true` and restart only the worker.

Manual review is enabled by default. Set `APP_ATFM_MANUAL_REVIEW_ENABLED=false`
to allow non-revision permits from review-only format profiles to continue through
the normal duplicate checks and ATFM write path. This bypass applies to every
review-only format, so use it only when that behavior is intended.

Duplicate handling uses both the original SHA-256 file hash and the target ATFM schedule. The same normalized permit with the same schedule is skipped successfully; changed schedule data reuses the existing permit master and appends only detail rows that are not already present.

For revision documents that contain an original schedule, AeroSync normalizes the
single permit value from the original schedule's `Original permit` / `Số phép bay
đã cấp` column and uses it as the ATFM target. The current document permit number
is retained as source data. Revision imports lock and compare the referenced
master, append only missing detail rows, and do not overwrite that master's
permit identity or metadata. If the original schedule names multiple different
permits, AeroSync does not choose one implicitly and retains the profile's
existing current-permit behavior.

### Replay a permit during local testing

The API can replay the exact archived email attachment without editing its permit number or sending the email again. This helper deletes the recorded ATFM master/detail rows first, but only when the master row still has `LASTUSER=AEROSYNC` and its IDs and normalized permit ID all match the tracking record.

Enable it only in a disposable test environment, with ATFM writes enabled:

```env
APP_ATFM_WRITE_ENABLED=true
APP_TEST_REPLAY_ENABLED=true
```

Then copy the job's `normalizedPermitId` from `GET /api/jobs/{id}` and use it as the explicit confirmation:

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/testing/jobs/123/replay `
  -ContentType application/json `
  -Body '{"confirmPermitId":"LD-06/A/S/2026"}'
```

The endpoint refuses active jobs, non-email jobs, duplicate jobs that do not own the target row, missing archived files, mismatched confirmations, and target rows not written by AeroSync. Keep `APP_TEST_REPLAY_ENABLED=false` everywhere except the intended test system.

For Gmail, use an [App Password](https://support.google.com/accounts/answer/185833), not your normal login password.

---

## Manual start (individual services)

If you prefer to run services yourself instead of `run-aerosync.bat`:

```powershell
# 1. Ensure Oracle XE, RabbitMQ, Redis are running locally

# 2. Build (from repo root)
.\aerosync-worker\mvnw.cmd -f pom.xml clean package -Dmaven.test.skip=true -pl aerosync-ingest,aerosync-worker,aerosync-api -am

# 3. Start each app (separate terminals)
.\scripts\start-worker.ps1
.\scripts\start-ingest.ps1
.\scripts\start-api.ps1
```

---

## API documentation

With `aerosync-api` running locally:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Swagger UI provides interactive documentation for the dashboard, job, configuration,
audit-log, alert, and email-report endpoints.

---

## Ports

Ensure these ports are free on the dev machine:

| Port | Service |
|------|---------|
| 1521 | Oracle Database listener |
| 5500 | Oracle EM Express (optional) |
| 5672 | RabbitMQ (AMQP) |
| 6379 | Redis |
| 8080 | AeroSync API |
| 15672 | RabbitMQ management UI |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Oracle listener unavailable | Oracle XE installation/service is incomplete or stopped | Start the Oracle XE database and `OracleOraDB21Home*TNSListener` services |
| `ORA-01017` | Incorrect AeroSync username/password | Run `scripts\setup-oracle.sql` and copy the same password to `.env` |
| `ORA-12514` | Wrong or unavailable service | Use `XEPDB1` and verify it appears in `lsnrctl status` |
| RabbitMQ not running | Service stopped | Start `RabbitMQ` in Services |
| Redis not running | Not installed or service stopped | Install Redis for Windows and start the service |
| Maven build fails | Wrong Java version | Install **Java 21** (`java -version`) |
| `API unavailable` in UI | API not started or port blocked | Check http://localhost:8080/api/dashboard/stats |
| UI build fails | Missing .NET / Windows SDK | Install .NET 8 SDK |
| Email not ingesting | Missing password or sender blacklisted | Fill `APP_EMAIL_*` in `.env`; run `.\scripts\test-imap.ps1` |
| File copied but no new job | Same file content (hash dedup) | Change file content or use a different file |
| Valid DOCX is quarantined with `ATFM-WRITE-DISABLED` | Target safety gate is active | Review the dry-run, enable `APP_ATFM_WRITE_ENABLED`, then retry |
| Permit is quarantined with `PERMIT-REVISION-REVIEW` | Same permit number has different schedule data | Compare the documents and resolve manually; AeroSync will not overwrite the ATFM permit |
| SUCCESS stays at 1 | Duplicate content re-ingested | Expected — only unique file hashes create new jobs |
| `ORA-01918: user VATM_USER does not exist` | Schema user was not created in XEPDB1 | Run `scripts\setup-oracle.sql` as SYSDBA |

---

## Project layout

```
VATM-AeroSync/
├── aerosync-ingest/     # File + email ingest service
├── aerosync-worker/     # Processing pipeline
├── aerosync-api/        # REST API
├── aerosync-ui/         # WinUI dashboard
├── aerosync-common/     # Shared library
├── .env.example         # Config template
├── run-aerosync.bat     # One-shot dev startup
└── scripts/             # Helper scripts
```

---

## Dev vs production

| | Development | Production (customer) |
|---|-------------|----------------------|
| **Database** | Local Oracle Database XE 21c | Customer's Oracle Database instance |
| **RabbitMQ / Redis** | Installed locally | Installed or managed servers |
| **Apps** | JARs via `run-aerosync.bat` | JARs as Windows/Linux services |
| **UI** | `dotnet build` + run EXE | Installed desktop app |

For customer deployment, connect to the target Oracle Database, RabbitMQ, and Redis, then configure `.env` with production service names and credentials.

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
copy aerosync-worker\src\test\resources\samples\valid-flights.csv D:\vatm-storage\incoming\

# Stop
.\run-aerosync.bat stop
```
