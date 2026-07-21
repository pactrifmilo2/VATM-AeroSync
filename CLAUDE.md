# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

Maven wrapper is at `aerosync-worker/mvnw.cmd` (Windows) / `aerosync-worker/mvnw` (Unix). Always run from repo root with `-f pom.xml`.

```powershell
# Full build (skip tests for dev speed)
.\aerosync-worker\mvnw.cmd -f pom.xml clean package -Dmaven.test.skip=true -pl aerosync-ingest,aerosync-worker,aerosync-api -am

# Run all tests (all modules)
.\aerosync-worker\mvnw.cmd -f pom.xml test -pl aerosync-common,aerosync-ingest,aerosync-worker,aerosync-api

# Run tests for a single module
.\aerosync-worker\mvnw.cmd -f pom.xml test -pl aerosync-worker -am

# Run a single test class
.\aerosync-worker\mvnw.cmd -f pom.xml test -pl aerosync-worker -am "-Dtest=FormatValidatorStepTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# Desktop UI build
dotnet build aerosync-ui\AeroSync.UI.csproj -p:Platform=x64
```

## Architecture Overview

VATM AeroSync is a multi-module **flight data synchronization platform** for the Vietnam Air Traffic Management organization. It ingests flight data files (CSV, XLSX, XML, JSON) from file-system drops and email attachments, processes them through a 6-step pipeline, stores normalized data in Oracle Database, and surfaces status via a REST API consumed by a WinUI 3 desktop dashboard.

**Four Maven modules** (`aerosync-common`, `aerosync-ingest`, `aerosync-worker`, `aerosync-api`) plus a separate .NET WinUI 3 project (`aerosync-ui`). All Java modules share the root parent POM (`vatm:aerosync-parent`), which extends `spring-boot-starter-parent:4.0.6` on **Java 21**.

### Module Responsibilities

- **`aerosync-common`** — Pure JAR library (no main class). Contains shared JPA entities (`SyncJob`, `FileRecord`, `AuditLog`, `EmailMetadata`), Spring Data repositories, DTOs, enums (`SyncStatus`, `FileSourceType`, `FileType`, `AlertLevel`), and configuration properties (`FilePathProperties`). Dependency of all three apps.

- **`aerosync-ingest`** — Spring Boot app. Scheduled file-system watcher (`FileSystemIngestService`) + IMAP email scanner (`EmailIngestService`). Both feed into `DeduplicationService` (SHA-256 hash check via Redis), then publish `FileIngestedEvent` to RabbitMQ `file.ingested` direct exchange.

- **`aerosync-worker`** — Spring Boot app. Consumes `FileIngestedEvent` from RabbitMQ, acquires a Redis distributed lock per file, then runs the `FileProcessingPipeline`: FormatValidator → Parser → Normalizer → BusinessRuleValidator → DatabaseWriter → FileArchiver. After completion publishes `SyncResultEvent` to `sync.result` fanout exchange. Also runs `RetentionCleanupJob` (scheduled) to delete old processed/error/quarantine files.

- **`aerosync-api`** — Spring Boot app. REST API on port 8080 serving dashboard stats, job listing/detail/retry, audit log search, and runtime config CRUD. Consumes `SyncResultEvent` from `sync.result` fanout for alert generation.

- **`aerosync-ui`** — WinUI 3 desktop app (.NET 8, C#). MVVM with CommunityToolkit.Mvvm. Connects to `http://localhost:8080`, auto-refreshes every 3 seconds. Vietnamese-language UI (labels: "Tổng quan", "Giám sát đồng bộ", etc.).

### Data Flow

```
FileSystem / Email → IngestService → DedupService(Redis) → RabbitMQ(file.ingested)
  → Worker consumer → ProcessingPipeline(6 steps) → Oracle Database + FileArchive
  → RabbitMQ(sync.result) → API AlertService → WinUI dashboard
```

### Processing Pipeline (worker, in order)

1. **FormatValidatorStep** — File existence, size ≤10MB, UTF-8 encoding, CSV header validation (requires: callsign, from, to, dateflight). Failures → `/error/` + `FAILED`.
2. **ParserStep** — Strategy pattern for CSV/XLSX/XML/JSON → `FlightRow` objects.
3. **NormalizerStep** — Trim, UPPERCASE callsign/airports, standardize dateFlight timezone.
4. **BusinessRuleValidatorStep** — Callsign `[A-Z0-9]{2,10}`, airport `[A-Z]{3}`, from≠to, dateFlight 2000-01-01 to +1 year. Failures → `/quarantine/` + `QUARANTINED` with per-row `RowValidationError`.
5. **DatabaseWriterStep** — `@Transactional`: deletes existing rows for job, inserts `FlightData` rows, sets `SUCCESS`. Full rollback on failure.
6. **FileArchiverStep** — Moves file to processed/error/quarantine with naming: `sender_YYYYMMDD_HHmmss_source[_prefix]_name.ext`.

### RabbitMQ Topology

| Exchange | Type | Queue | Purpose |
|----------|------|-------|---------|
| `file.ingested` | direct | `file.processing.queue` | Ingest → Worker (maxPriority=10 for VIP) |
| `sync.result` | fanout | `dashboard.alerts.queue` | Worker → API notifications |

### Database

Oracle Database XE 21c, using the `XEPDB1` pluggable database, with tables including `sync_jobs`, `file_records`, `audit_logs`, `email_metadata`, `flight_data`, and `runtime_config`. JPA `ddl-auto: update` is used in all environments. Test application profiles use H2 in Oracle compatibility mode (`jdbc:h2:mem:<name>;MODE=Oracle;DB_CLOSE_DELAY=-1`); JPA slice tests use Spring Boot's embedded H2 replacement.

### Configuration Loading

Each module's `application.yaml` imports `.env` from the repo root via:
```yaml
spring.config.import: optional:file:${AEROSYNC_CONFIG_DIR:${user.dir}}/.env[.properties]
```
The `.env` file is gitignored; `.env.example` is the committed template. Startup scripts set `AEROSYNC_CONFIG_DIR` to the repo root.

### Key Business Rules

- **BR-01 (Idempotency)**: SHA-256 hash of file content; same hash + terminal status → skip.
- **BR-02 (Atomic batch)**: Full `@Transactional` rollback if any row fails.
- **BR-03 (Retention)**: Processed files deleted after 60 days, error/quarantine after 90 days.
- **BR-05 (Priority)**: Files with URGENT/VIP in filename or subject get RabbitMQ priority=10.
- **BR-06 (Rate limit)**: Max 100 files per ingest cycle.
- **BR-07 (Audit)**: 100% audit logging via `AuditLogService` — every pipeline action recorded with input/output summary, duration, and result.

### Test Approach

- **Common**: `@DataJpaTest` with H2 for repositories, Jackson round-trip tests for DTOs.
- **Ingest/Worker/API**: `@SpringBootTest` with mocked external deps (RabbitMQ, Redis) for unit tests; Testcontainers for infrastructure integration tests (`*IntegrationTest`).
- **Worker pipeline steps**: Each step has its own test class (e.g., `FormatValidatorStepTest`, `ParserStepTest`).
- **API controllers**: Tested with `MockMvc`.
- **Sample test data**: `aerosync-worker/src/test/resources/samples/` (valid-flights.csv, .json, .xml, temp.xlsx).
- Test config files (`application-test.yaml`) disable live service connections and use H2 in-memory databases.

### Key Directories

```
aerosync-common/src/main/java/vatm/aerosync/common/   # Shared entities, repos, DTOs
aerosync-ingest/src/main/java/vatm/aerosync/ingest/    # Ingest services, email client
aerosync-worker/src/main/java/vatm/aerosync/worker/    # Pipeline steps, consumers
aerosync-api/src/main/java/vatm/aerosync/api/          # REST controllers, services
aerosync-ui/                                           # WinUI 3 desktop app (separate build)
scripts/                                               # PowerShell startup/test helpers
```
