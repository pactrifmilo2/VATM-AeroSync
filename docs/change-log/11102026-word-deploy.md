# Change log 11102026 - Word 08-09/10 and deploy/UI

## Goal and assumptions
- Only code, release scripts and Markdown were edited; Word files in `E:\09` and `D:\vatm-storage` were not moved/rewritten/replayed. No ATFM writes were performed in this audit.
- Repo already has unrelated changes. Commit only the files in this log. `processing` does not exist in `D:\vatm-storage`.
- New profiles are scoped to carrier and `reviewOnly`. Keep `APP_ATFM_MANUAL_REVIEW_ENABLED=true` when ATFM writes are enabled. Quarantine files lack adjacent `.log`; parsing is not proof of a valid DB baseline.

## Word and YAML changes
- `E:\09`: before 20/23 Word parsed, two lacked TAX/DHX profiles, one lacked the EOBT alias. After: 23/23 parsed by local corpus test (not proof of ATFM insert readiness).
- 08/10: 17 Word in `error`, 15 in `quarantine`. After: 26/32 parsed and validated locally; two remain blocked by FOC (ambiguous in ATFM) and NMI (absent in ATFM), four are not issued permits: QR schedule, Turkish application, RIX schedule, QQE schedule. Previously quarantined files still require baseline checks; do not replay automatically.
- `aerosync-worker/src/main/resources/permit-formats/`: 14 new carrier-scoped profiles: PTA/CCA/CXA/CES/CSZ seasonal landing, QTR seasonal landing/overflight, TAX/DHX seasonal overflight, SIAM LAND overflight, SINOJET/KXP landing revision, OAH/QQE landing review. All new profiles are `reviewOnly`.
- `aerosync-worker/src/main/resources/permit-reference/airport-codes.yaml`: add only unique IATA/ICAO mappings confirmed by read-only ATFM.M_AERO query. FOC returns two codes (ZSFC/ZSFZ), NMI has no match: neither is mapped; affected permits must be verified against the DB/source before any import.
- `aerosync-worker/src/main/java/vatm/aerosync/worker/pipeline/DocxSchedulePermitParser.java`: recognize narrowly the printed `09OOCT26` typo as OCT in date cells; never edit source Word or guess other invalid dates.
- `aerosync-worker/src/main/java/vatm/aerosync/worker/service/PermitImportCoordinator.java`: manual-review enforcement now covers revision as well as issued permits; added regression tests in `aerosync-worker/src/test/java/vatm/aerosync/worker/{pipeline/October08And09PermitProfilesTest.java,service/PermitImportCoordinatorTest.java}`.

## Deploy and UI changes
- `aerosync-ingest/src/main/java/vatm/aerosync/ingest/service/RuntimeIngestionConfig.java`: annotate the Spring injection constructor, fixing the observed `No default constructor found` crash; regression test in `aerosync-ingest/src/test/java/vatm/aerosync/ingest/service/RuntimeIngestionConfigWiringTest.java`.
- `deployment/deploy/AeroSync-Deploy.ps1`: require app PRI, validate checksums using separate expected hash/path, wait for API and verify all three services remain running for 15 seconds instead of false success after ingest exits. `deployment/deploy/Open-AeroSync.ps1` now reports a missing PRI directly.
- `scripts/release.ps1`: bundle `AeroSync.UI.pri` from WinUI build output when `dotnet publish` omits it, with SHA256SUMS entry. `deployment/DEPLOYMENT-GUIDE.md` includes operating instructions.
- Test artifact `dist/aerosync-0.0.1-20261011-184220.zip` was generated with PRI and final deployment/launcher scripts. No package was installed into `C:\AeroSync`.

## Checks actually performed
- Corpus `E:\09`: 23 success/0 failure; 08/10 Word with safe airport mapping: 26 success/2 failures/4 non-permits (expected corpus assertion failure for FOC/NMI).
- Maven non-Docker tests passed with `-Dtest=*,!IngestIntegrationTest,!WorkerIntegrationTest,!ApiIntegrationTest`; targeted real-document tests for E:\09 and error/08 passed; Maven package of the three JARs passed.
- Unfiltered Maven tests require Docker/Testcontainers and failed due to missing Docker (not claimed to pass).
- `dotnet publish` Release x64 self-contained passed, publish output omitted PRI. Copying the built PRI to the test publish let the UI process survive a five-second smoke launch without new XamlParseException. Full interaction on the installed UI was not tested.
- PowerShell syntax checks passed for release, deploy and launcher. Final stage has PRI, 340 embedded worker YAML profiles and 521/521 matching SHA256SUMS entries; deployment/launcher scripts match current source.

## Follow-up: 08/10 source files still blocked
- `D:\vatm-storage\error\2026\10\08\giangpth_20261008_144837_email_001_LD_15_A_W_2026VN_MF_0a911a96.doc`: CXA, FOC has two ATFM airport mappings; require DBA/operational choice before writing.
- `D:\vatm-storage\quarantine\2026\10\08\duongvtt_20261008_144819_email_quarantine_000_LD-_3680-O9_08-09OCT-HAN_hav_e45b89fc.docx`: OAH, NMI absent from ATFM.M_AERO; cannot safely map.

## Remaining limits
- Installed `C:\AeroSync` still has old files and previously showed ingest failure / UI XamlParseException. Install the final repackaged release only during an approved service maintenance window after backing up .env and confirming manual-review settings. Do not replay user documents automatically.
- Verify FOC/NMI with data owner/ATFM and check quarantine revisions against the original permit before reprocessing; these are not safe auto-fixes. Smoke launch is not a full deployed UI test.


## Word corpus 13/10/2026 (E:\Email_NOT_FORMAT\13)

### Scope and assumptions
- One Word file: `haanhlv_20261013_103551_email_005_OF-93.A.W.2026VN.CLX_7287ce4f.docx`. Files were read only; the source document was not modified and no ATFM write was performed.
- Before the change the corpus test failed with "File chua co dinh dang YAML phu hop", identity IATA=CV / ICAO=CLX: no carrier profile existed.

### Files changed
- `aerosync-worker/src/main/resources/permit-formats/clx-caav-seasonal-overflight-issued.yaml` (new): scoped Cargolux seasonal CAAV overflight profile extending `airline-generic-seasonal-overflight-issued`, detecting `OF-<n>/A/W/<year>VN` together with IATA CV, ICAO CLX and the "Cargolux" carrier name; declares EOBT/EIBT column wording and the aircraft table wording used by this carrier; `reviewOnly: true`.
- `aerosync-worker/src/test/java/vatm/aerosync/worker/pipeline/October13CargoluxProfileTest.java` (new): regression test pinned to the real file via `-Dpermit.october13.corpus.dir`, asserting profile id, permit id `O/F 0093A/W/CHK/2026`, operator CLX, 66 flights, purpose CAR, ICAO 4-letter airports and review-only.

### Behaviour before and after
- Before: 0 success / 1 failure, no profile matched.
- After: 1 success / 0 failure; profile `clx-caav-seasonal-overflight-issued`; the profile stays review-only, so this permit is not auto-written to ATFM.

### Checks run
- `WordPermitCorpusRegressionTest` on `E:\Email_NOT_FORMAT\13`: 1 success / 0 failure / 0 non-permit.
- `October13CargoluxProfileTest` with `-Dpermit.october13.corpus.dir=E:\Email_NOT_FORMAT\13`: pass.
- Regression: corpus `E:\09` 23/23 pass; 08/10 corpus unchanged at 26 success / 2 known failures (FOC ambiguous in ATFM, NMI absent in ATFM).
- Full non-Docker unit/API suite (`-Dtest=*,!IngestIntegrationTest,!WorkerIntegrationTest,!ApiIntegrationTest`): pass.

### Remaining limits
- Docker/Testcontainers integration tests were not run (no Docker on this host), same as the earlier 11/10 task.
- The permit is parsed but still `reviewOnly`; enabling automatic import needs ATFM review (reference data, aircraft types B747-8F/B747-400F and baseline) and a separate decision.
- No release package was rebuilt for this profile change; `dist\` artifacts and `C:\AeroSync` still carry the 11/10 build.


## Fix revision 17/10/2026 - job 18867 (OF-7563 REV1)

### Root cause
- Job `18867` failed with `BR-REVISION-ROW-MAPPING-AMBIGUOUS: Cannot pair NEW with OLD for
  unchanged fields: VPCDI`. The CAAV revision document keeps the same flight number, effective
  interval and UTC times but changes one endpoint (`WSSL` -> `WSSS` on 18/10/2026). The reconciler
  paired OLD/NEW rows only by exact route or by a unique callsign, so the changed endpoint produced
  no match even though the pairing was unique.
- Secondary symptom: after the quarantine archive, the job was republished many times and failed
  with "File does not exist or is not a regular file" because the retry used the archived path
  from `file_records.stored_path` while the event referenced the staging path.

### Files changed
- `aerosync-worker/src/main/java/vatm/aerosync/worker/pipeline/RevisionReconciliationStep.java`:
  added `findUniqueByStableScheduleFields`, used only for non-seasonal revisions and only when the
  flight number, effective interval, ETD and ETA identify exactly one OLD row. Ambiguous cases
  still raise `BR-REVISION-ROW-MAPPING-AMBIGUOUS`.
- `aerosync-worker/src/test/java/vatm/aerosync/worker/pipeline/RevisionReconciliationStepTest.java`:
  added `reconcile_nonScheduledRevisionPairsChangedEndpointByStableScheduleFields` covering the
  WSSL -> WSSS case.

### Behaviour before and after
- Before: revision quarantined with `BR-REVISION-ROW-MAPPING-AMBIGUOUS`.
- After: the same document parses to `O/F 07563/CHK/2026` with 2 schedule rows; reconciliation
  pairs the OLD row for `VPCDI` without relying on row position.

### Checks run
- `RevisionReconciliationStepTest`: pass (including the new test).
- `WordPermitCorpusRegressionTest` on the job 18867 file: 1 success / 0 failure, and the same
  result with `-Dpermit.corpus.resolve-aircraft=true` against live ATFM reference data.
- Regression: `E:\09` 23/23 pass.
- Non-Docker unit/API suite: pass.

### Remaining limits
- The deployed `C:\AeroSync\app\worker.jar` (build 13/10 16:40) does NOT contain this fix; the
  corrected worker must be rebuilt and redeployed before job 18867 can be retried.
- The repeated archive-path retries were observed but not changed; only the revision mapping fix
  is implemented here. The retry loop needs a separate decision on which path is republished.
