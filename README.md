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

The initialization script creates only AeroSync-owned tables, indexes, constraints,
and the `FLIGHT_DATA_SEQ` sequence. It can be run again safely: existing tables and
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
| `APP_FILE_PATHS_*` | `C:/vatm-storage/...` | Created automatically on first run |
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
copy aerosync-worker\src\test\resources\samples\valid-flights.csv C:\vatm-storage\incoming\
```

Wait for the next ingest cycle (or set `APP_INGEST_SCHEDULER_FIXED_DELAY_MS=30000` in `.env` and restart). Then check:

- http://localhost:8080/api/dashboard/stats — `SUCCESS` count should increase
- UI dashboard — job appears in the sync list

### 8. Confirm data in Oracle Database (optional)

```powershell
sqlplus vatm_user@localhost:1521/XEPDB1
SELECT COUNT(*) FROM flight_data;
```

Or use an Oracle client connected to the `XEPDB1` service on `localhost:1521` as `VATM_USER`.

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

### Scheduled permit Word import

The worker recognizes CAAV landing and overflight permits in both `.doc` and `.docx`
files. This includes issued and revised forms, English and Vietnamese headers,
IATA/ICAO airport variants, IATA/ICAO flight-number prefixes, and the recurring
aircraft and schedule layouts used by CAAV.

You do not need to import files one at a time. Copy a whole folder into the incoming
directory:

```powershell
Copy-Item -Path 'C:\source-permits\*.doc*' -Destination 'C:\vatm-storage\incoming'
```

The ingest service accepts up to 100 files per scan by default, so 50 documents are
discovered in one cycle and then processed independently. Set
`APP_INGEST_MAX_FILES_PER_CYCLE` if a larger batch is required. Each permit still
uses its own target transaction: one master row in `T_PERMMASTER_SC`, followed by
its schedule rows in `T_PERMDETAIL_SC` using the generated `PERM_ID`.

Format recognition uses a shared semantic extractor followed by a profile policy;
it is not automatic machine-learning training. The shared layer ranks the main
permit header above cited permits, recognizes common dates/operator/address
labels, adapts table headers, and understands original/replacement/supplemental
schedule sections. Profiles under
`aerosync-worker/src/main/resources/permit-formats` provide normalization,
defaults, validation rules, and special overrides. Ordinary wording and layout
variants should therefore work without a new profile; only a new permit family
or business policy needs one.

Revision forms are parsed and validated, but are marked `REVISION_REVIEW` instead
of being written automatically. This prevents a revision from silently replacing
an existing ATFM permit. For legacy revisions such as `LD-06/A/S/2026VN/REV8`, the
worker uses only the new-schedule table and normalizes the permit identity as
`LD-06/A/S/2026`.

Adaptive matches and revision permits now create a persistent operator-review
record. The admin UI can use these API endpoints:

| Method | Endpoint | Role | Purpose |
|--------|----------|------|---------|
| `GET` | `/api/permit-reviews` | Operator/Admin | List and filter the review queue |
| `GET` | `/api/permit-reviews/{id}` | Operator/Admin | Read parsed values, diagnostics, and corrections |
| `PUT` | `/api/permit-reviews/{id}/correction` | Operator/Admin | Save corrected permit data |
| `POST` | `/api/permit-reviews/{id}/approve` | Operator/Admin | Confirm and freeze the reviewed data |
| `POST` | `/api/permit-reviews/{id}/reject` | Operator/Admin | Reject the review with a reason |
| `POST` | `/api/permit-reviews/{id}/publish` | Admin | Queue a separately approved permit for ATFM publication |

Approval and publication are deliberately separate. Approval records the human
decision and creates a reusable correction sample; publication is the
higher-risk ATFM write. Publication remains subject to
`APP_ATFM_WRITE_ENABLED=true` and worker validation.

Approved reviews can also produce safe table-header training candidates. This is
a third, separate decision: permit approval does not automatically teach the
parser. The admin UI can use these endpoints:

| Method | Endpoint | Role | Purpose |
|--------|----------|------|---------|
| `GET` | `/api/permit-training-candidates` | Admin | List/filter pending, approved, rejected, or disabled candidates |
| `GET` | `/api/permit-training-candidates/groups` | Admin | Group identical evidence from independent approved reviews |
| `GET` | `/api/permit-training-candidates/{id}` | Admin | Inspect evidence, validation, usage, and the source review |
| `GET` | `/api/permit-training-candidates/{id}/preflight` | Admin | Check the evidence threshold, conflicts, and replay status |
| `GET` | `/api/permit-training-candidates/{id}/history` | Admin | Read the immutable decision and validation history |
| `POST` | `/api/permit-training-candidates/{id}/validate` | Admin | Ask the worker to replay retained source permits |
| `POST` | `/api/permit-training-candidates/{id}/approve` | Admin | Activate an alias only after preflight passes |
| `POST` | `/api/permit-training-candidates/{id}/reject` | Admin | Reject the candidate with a reason |
| `POST` | `/api/permit-training-candidates/{id}/disable` | Admin | Immediately remove an active alias from parsing |
| `POST` | `/api/permit-training-candidates/{id}/reactivate` | Admin | Restore a disabled alias after a fresh successful replay |

Only shared or fuzzy table-header matches are eligible for automatic candidate
creation. By default, activation requires the same profile/version, semantic
field, and canonical alias from at least two independent approved reviews. The
worker then reparses every retained source document in that evidence group with
the proposed alias and verifies that the selected profile and extracted permit
do not change. Configure the threshold with
`APP_PERMIT_TRAINING_MINIMUM_EVIDENCE`; corpus replay can be disabled only as an
explicit operational override with
`APP_PERMIT_TRAINING_REQUIRE_CORPUS_VALIDATION=false`.

The normal admin flow is: inspect the grouped evidence, read `preflight`, call
`validate`, poll the candidate until `validationStatus` is `PASSED`, and then
call `approve`. Approved candidates are profile-scoped, loaded from the
AeroSync database for each new permit, and treated as trusted exact aliases.
The API reports `usageCount` and `lastUsedAt` after the worker uses an alias.
Disabling an alias makes it inactive for the next parse without deleting its
history; reactivation requires a fresh replay. Aliases also stop applying
automatically if the YAML profile version changes. Corrections to business
values such as operator, dates, or flight data remain evidence for developers
and are never converted into executable rules automatically.

Guided profile training is available for permit families that cannot be handled
by the shared extractor plus the existing YAML overlays. It uses the structured
document retained during ingest, so an operator labels stable Word cell IDs or
selected text with semantic names such as `operator.icao` and
`schedule.flightNumber`. The definition format contains no regular expressions
or executable code.

| Method | Endpoint | Role | Purpose |
|--------|----------|------|---------|
| `GET` | `/api/permit-training-sources` | Operator/Admin | Find retained Word sources that can be labeled |
| `GET` | `/api/permit-training-sources/{id}` | Operator/Admin | Read the structured text, tables, and stable cell IDs |
| `POST` | `/api/permit-training-sources/{id}/retain` | Operator/Admin | Extend retention before using a source for training |
| `GET` | `/api/permit-training-profiles` | Operator/Admin | List draft and evidence-collection profile versions |
| `GET` | `/api/permit-training-profiles/{id}` | Operator/Admin | Read a definition, evidence, and immutable history |
| `POST` | `/api/permit-training-profiles` | Operator/Admin | Create the next draft version from a retained source |
| `PUT` | `/api/permit-training-profiles/{id}/definition` | Operator/Admin | Save validated field and table labels |
| `POST` | `/api/permit-training-profiles/{id}/evidence` | Operator/Admin | Attach the corrected permit that the source should produce |
| `DELETE` | `/api/permit-training-profiles/{id}/evidence/{evidenceId}` | Operator/Admin | Remove evidence before the mapping is confirmed |
| `POST` | `/api/permit-training-profiles/{id}/confirm` | Operator/Admin | Lock the mapping and move it to evidence collection |

The Phase 2 workflow is `DRAFT -> COLLECTING_EVIDENCE`. Confirmation is not
activation: these APIs cannot create compiled rules, set a profile to `ACTIVE`,
or affect live mail parsing. Activation requires later compilation, replay,
canary, and admin-promotion phases. Every mutation uses an `expectedVersion`
value to prevent one operator from silently overwriting another operator's
changes. The tables used by this workflow are already created by
`scripts/migrate-adaptive-permit-review-oracle.sql`; Phase 2 adds no new
database migration.

Before deploying this phase to an existing database, rerun the idempotent
`scripts/migrate-adaptive-permit-review-oracle.sql` migration. Previously
approved aliases are left active to avoid a silent production behavior change;
an administrator can disable, validate, and reactivate them to bring them
through the new gate.

Review API authentication reuses the legacy `T_USERS` and `T_USERMENU` tables.
Only active users with edit access to the configured permit menu can review
permits. The configured main account must also have publish access and is
mapped to `ADMIN`; other eligible users are mapped to `OPERATOR`. The defaults
match the current ATFM installation:

```properties
APP_LEGACY_ADMIN_USERNAME=admin
APP_LEGACY_PERMIT_MENU_ID=403
```

Password verification is compatible with the legacy .NET login: MD5 of the
UTF-16LE password bytes formatted as uppercase hexadecimal pairs separated by
hyphens. The test console uses Spring Security's one-time form login and keeps
the authenticated identity in the server session. Review APIs also retain HTTP
Basic support for non-browser clients; other existing monitoring endpoints keep
their current access behavior.

For controlled testing before these workflows are added to the permanent admin
page, the API includes an optional same-origin browser console. Enable it only
in a test environment:

```env
APP_PERMIT_REVIEW_TEST_UI_ENABLED=true
```

Restart the API and open `http://localhost:8080/permit-review-test` (replace the
host and port if the API runs elsewhere). The console reuses the browser's
existing same-origin AeroSync authentication and does not collect or store
credentials. With the current HTTP Basic setup, the browser may show its native
login prompt once when opening the protected page. An `OPERATOR` account can
test review, correction, approval, and rejection; an `ADMIN` account also
unlocks ATFM publication and alias validation, activation, disabling,
reactivation, and history. The feature is disabled by default and should remain
disabled in production.

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
```

Writes are disabled by default. With the flag disabled, a valid permit is recorded as `DRY_RUN` and quarantined so it cannot be mistaken for a successful target insert. Run `scripts/migrate-phase5-oracle.sql` against the AeroSync tracking schema before enabling imports. After review, set `APP_ATFM_WRITE_ENABLED=true` and restart only the worker.

Duplicate handling uses both the original SHA-256 file hash and a semantic schedule hash. The same normalized permit with the same schedule is skipped successfully; changed schedule data for the same permit is quarantined for manual review.

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
copy aerosync-worker\src\test\resources\samples\valid-flights.csv C:\vatm-storage\incoming\

# Stop
.\run-aerosync.bat stop
```
