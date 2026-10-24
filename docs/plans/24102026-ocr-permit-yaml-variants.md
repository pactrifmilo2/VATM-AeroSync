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
- [ ] B - Add the Numero sign to every YAML with a permit-number pattern.
  - Benefit: broad coverage.
  - Cost: may alter unrelated formats and increase false matches.
- [ ] C - Other: ____________________
- **User choice:** not explicitly confirmed. Implementation proceeded using recommended option A as a scoped default, per the user's continuing request to make the changes.

### 2. OCR handling for aircraft and route fields

- [ ] A - **Recommended:** Add controlled OCR aliases for observed headers (for example `Aircraft Type`, `AircraftType`, `Aircrafl Type`, `Airways`, `Airway`, `Route`, `Sector(s)`); extend route separators only where parser support exists. Missing or non-unique columns remain review/error; do not substitute defaults.
  - Benefit: avoids inventing operational data.
  - Cost: severely degraded OCR still requires review.
- [ ] B - Infer aircraft/route values from cell contents when headers cannot be read.
  - Benefit: potentially higher automatic extraction rate.
  - Cost: risks assigning an incorrect aircraft type, registration or route.
- [ ] C - Other: ____________________
- **User choice:** not explicitly confirmed. Implementation proceeded using recommended option A as a scoped default; ambiguous values remain review/error and are not inferred.

## Before implementation

Implemented as a scoped default in CAAV generic/revision/seasonal profiles. Added regressions for joined/spaced numbers, the Numero prefix, and OCR-damaged aircraft/route headers. No fallback values are inferred for ambiguous aircraft or route data.

## Verification and commit

Verification and commit status are recorded in `docs/change-log/24102026.md`. Changes are intended for a scoped local commit only; do not push.
