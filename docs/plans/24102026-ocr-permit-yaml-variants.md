# Plan 24102026 - OCR variants for permit and schedule tables

## Objective

Extend permit YAML profiles to recognize permit numbers prefixed by the Numero sign (U+2116) and inconsistent OCR whitespace, while improving recognition of aircraft and route fields when headers or content have OCR errors. Do not infer aircraft/route values when evidence is ambiguous.

## Current state checked

- The catalog contains hundreds of profiles in `aerosync-worker/src/main/resources/permit-formats/`.
- `caav-generic-seasonal-landing-revision.yaml` recognizes `LD-72/A/W/2026VN` with whitespace around separators, but not a leading Numero sign and inherits `reviewOnly: true` from its parent.
- `caav-generic-landing-issued.yaml` recognizes monthly/year `LD` numbers and has schedule aliases for aircraft type. Other profiles have their own aliases, so a catalog-wide change needs evidence and tests.
- The worktree contains staged and unstaged user changes, including permit YAMLs. Any later commit must include only files created for this request.

## Decisions to confirm

### 1. Profile scope for Numero sign and spaced permit numbers

- [ ] A - **Recommended:** Extend relevant CAAV generic/revision/seasonal profiles and any profiles with matching source evidence; leave unrelated airline profiles unchanged. Include `LD  - 72/A/W/2026VN`, multiple spaces, tabs/NBSP around separators and number components.
  - Benefit: targets matching formats and limits cross-airline risk.
  - Cost: identify the applicable seasonal/non-seasonal profiles and add regression tests.
- [X] B - Add the Numero sign to every YAML with a permit-number pattern.
  - Benefit: broad coverage.
  - Cost: may alter unrelated formats and increase false matches.
- [ ] C - Other: ____________________
- **User choice: B (confirmed in the plan).** Apply the optional Numero prefix to every YAML permit-number pattern. This supersedes the earlier scoped CAAV-only implementation; unrelated airline profiles must also be audited and updated.

### 2. OCR handling for aircraft and route fields

- [ ] A - **Recommended:** Add controlled OCR aliases for observed headers (for example `Aircraft Type`, `AircraftType`, `Aircrafl Type`, `Airways`, `Airway`, `Route`, `Sector(s)`); extend route separators only where parser support exists. Missing or non-unique columns remain review/error; do not substitute defaults.
  - Benefit: avoids inventing operational data.
  - Cost: severely degraded OCR still requires review.
- [ ] B - Infer aircraft/route values from cell contents when headers cannot be read.
  - Benefit: potentially higher automatic extraction rate.
  - Cost: risks assigning an incorrect aircraft type, registration or route.
- [X] C - Other: áp dung cả A và B
- **User choice: C, "áp dụng cả A và B" (confirmed in the plan).** Add controlled OCR header aliases and infer aircraft/route fields from values when headers are unreadable.

## Before implementation

Initial CAAV-only changes are committed in `a8f94e3b`. Follow-up scope is broader: update every permit-number YAML pattern, then add headerless aircraft/route inference with ambiguity rejection. Profile inference is limited to tables outside identified schedule tables. Aircraft type must match a known catalog alias or an unambiguous aircraft code candidate; route requires one unique airport pair and a structurally valid airway string. Registration marks require a conservative registration pattern. Ambiguous candidates remain review/error, never selected by position alone.

## Verification and commit

Inventory: 358 YAML profiles, including 101 with `permit.pattern`. The broad update must inventory all profiles, test every permit pattern with a Numero-prefixed value, add tests for headerless aircraft/route tables and ambiguity, run the worker suite, update `docs/change-log/24102026.md`, then commit only the files changed for the follow-up. Do not push.
