# Audit Word permits (offline, repeatable)

The inventory and audit are **read-only against source storage and ATFM**. The Java audit is an opt-in test, not a production pipeline, and constructs no JDBC gateway, repository or database writer. Apache POI generates and reopens the Excel workbook. Local Word documents and generated reports are not committed.

## Run

From the repository root, with Python 3.11+ and the project's Java/Maven toolchain:

```powershell
python -B scripts/audit-permit-corpus.py --storage D:/vatm-storage --start 2026-09-01 --end 2026-09-25 --output .tmp/permit-audit-25092026
mvn -pl aerosync-worker -am test "-Dtest=PermitCorpusAuditTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dpermit.audit.manifest=E:/ATFM-AEROSYNC/VATM-AeroSync/.tmp/permit-audit-25092026/manifest.json" "-Dpermit.audit.output=E:/ATFM-AEROSYNC/VATM-AeroSync/.tmp/permit-audit-25092026"
```

Change paths and dates explicitly. `manifest.json` captures the inventory snapshot, SHA-256, all in-period aliases, storage state, date and date basis. Files are rehashed before parsing to detect source changes. A UTF-8 BOM is accepted by both manifest readers. No source file is moved, edited, replayed or deleted.

## Scope and denominator

- Scan `incoming`, `processed`, `error`, `quarantine`, `error-format`, including nested email attachments, `.doc`, `.docx` and hashed extension suffixes. Exclude Word lock files (`~$`). Missing directories/read errors are reported, not silently discarded.
- For `incoming/email-attachments/YYYYMMDD`, use its folder date, **not** the old nested attachment name. Otherwise prefer the outer ingest timestamp, then dated storage folder, then filesystem mtime. Dates are **local storage proxies**, not a verification against `EmailMetadata.receivedAt`. This distinction does not change the production reference rule based on email receipt.
- Date bounds are inclusive. Deduplicate the selected physical files by SHA-256. Identical attachments resent in the interval count once. Different-byte Word files remain separate even if semantically similar.
- `READABLE` = detect + parse + normalize + source-field validation. Deferred CRAFT_ID and 3-letter IATA-to-ICAO catalog lookups are exposed in **Pending catalog fields** rather than simulated with fabricated IDs. A000 is not counted as resolved aircraft. All resolution/import cells are `NOT_TESTED`.
- Rate = `READABLE / (unique samples - confirmed NON_PERMIT)`. Errors and unknown documents stay in the denominator; zero samples = `N/A`, not 0% or 100%. Profiles use the same non-permit exclusion. Rates describe this local storage snapshot, not all airline emails or production imports.
- Application/ancillary document classification uses explicit heading/structure evidence; documents with no recognizable permit identity but no non-permit evidence remain `UNCLASSIFIED`. The workbook retains file/hash evidence for review.

## Outputs

- `permit-format-audit.xlsx`: **Method**, **Profiles**, **Airlines**, **Files**, **Errors**, **Aliases**. Numeric counts/rates, frozen headings, filters; every registered profile appears, including profiles with no samples.
- `results.json`: per-hash result, profile, carrier, parsed row counts, aircraft/purpose fields, errors, pending catalog fields and all aliases.
- `documents/<SHA256>.txt`: local extracted paragraph/table evidence. This directory can contain personal information from source documents. Keep local; do not commit or upload it.
- Workbook regeneration only (no new parsing): add `-Dpermit.audit.results=<prior results.json>` using the **same manifest and unchanged source/profile version**. Regeneration verifies hash order/count but does not certify a new parse run.

## Validation and limits

```powershell
python -B -m unittest discover -s scripts -p test_audit_permit_corpus.py -v
mvn -pl aerosync-worker -am test "-Dtest=*,!WorkerIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl aerosync-worker -am package -DskipTests
```

`WorkerIntegrationTest` needs a working Docker environment; do not call a run that excludes it a full integration pass. Optional local-file fixture tests report skips when not configured. The audit itself checks complete row counts and workbook read-back; it intentionally reports malformed corpus files instead of failing before the error report can be generated. A successful audit run therefore does **not** mean every Word file passed parsing.

Do not infer missing permit prefixes, repair conflicting dates or invalid times, join ambiguous route cells, or replay production jobs merely to increase the rate. These require separate source review and, where multiple interpretations materially differ, confirmed business decisions.
