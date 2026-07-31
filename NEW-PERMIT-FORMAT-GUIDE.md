# Adding a New Permit Format

This guide explains where to make changes when AeroSync receives a Word permit
that looks different from the samples already tested.

## DOCX formats

Do not create a profile for every wording, spacing, accent, or table-header
variation. Every `.docx` first goes through the shared semantic extractor, which:

- Ranks permit identities found in the document header above references found in
  schedule tables.
- Recognizes common permit-date, ICAO/IATA, and address labels.
- Classifies section 2.1 as the original schedule, 2.2 as the replacement
  schedule, and 2.5 as a supplemental schedule.
- Matches shared table aliases after normalizing case, accents, punctuation, and
  whitespace, with conservative fuzzy matching for small spelling differences.

The selected profile is then an overlay. It supplies the permit's business rules,
normalization templates, defaults, validation policy, and any layout-specific
override. In other words:

```text
Word document
  -> shared semantic evidence
  -> profile policy and trusted overrides
  -> validation and operator review
```

The shared implementation lives in:

```text
aerosync-worker/src/main/java/vatm/aerosync/worker/pipeline/PermitSemanticExtractor.java
aerosync-worker/src/main/resources/permit-reference/permit-semantic-aliases.yaml
```

Add a new YAML profile only when the document represents a genuinely different
permit family or needs different business behavior. Java registration is not
required because `DocxPermitProfileCatalog` automatically loads every file matching:

```text
aerosync-worker/src/main/resources/permit-formats/*.yaml
```

When a new profile is justified, start by copying the existing profile that most
closely resembles the new document. For example:

```text
aerosync-worker/src/main/resources/permit-formats/caav-generic-landing-issued.yaml
```

Rename the copy to something descriptive, such as:

```text
caav-english-charter-overflight.yaml
```

## 1. Configure format recognition

The beginning of a genuinely new profile identifies its permit family:

```yaml
id: caav-english-charter-overflight
family: caav-english
profileVersion: 1
priority: 10

detectionPatterns:
  - '(?iu)\bOVERFLIGHT\s+PERMIT\b'
  - '(?iu)\bCHARTER\s+FLIGHT\b'
  - '(?iu)\bOF\s*-\s*\d+'
```

Current detection behavior:

- Candidate confidence combines permit identity (40%), schedule structure (40%), and detection signals (20%).
- A candidate must reach `0.90` confidence. Partial detection may parse, but it is marked for operator review.
- The highest `priority` eligible profile is preferred, preserving specific profiles over generic fallbacks.
- Equal top candidates with the same priority and confidence are rejected as ambiguous.
- Generic fallback profiles normally have a low priority, such as `-100`.
- Increase `profileVersion` whenever a deployed profile changes.

When adaptive detection requires review, the worker stores the parsed permit,
profile candidates, field diagnostics, and warnings in `permit_reviews`. An
operator can correct and approve that snapshot through
`/api/permit-reviews`. Approval does not write to ATFM; an administrator must
make the separate `/publish` request.

Approved adaptive header matches create pending candidates under
`/api/permit-training-candidates`. A separate administrator decision is required
to activate a candidate. Before approval, the default safety gate requires two
independent approved reviews with the same profile/version, semantic field, and
canonical header. Use the grouped-evidence and preflight endpoints, then request
`/{id}/validate`. The worker replays all retained source Word documents with the
candidate in preview mode and requires the extracted snapshots to remain
unchanged. The preview alias is not active for live mail while validation runs.

After validation passes, an approved candidate is added as a trusted exact alias
only to the profile and profile version that produced it; the worker loads these
aliases from the database for later permits without rewriting YAML. Runtime
usage is counted. An administrator can disable an alias immediately and inspect
its permanent history; reactivation requires a fresh successful replay. If the
YAML profile version changes, the candidate becomes inactive until it is
reviewed again. Business-value corrections are retained as evidence but are
never turned into executable parser rules automatically.

For a genuinely new family, the guided training API can now collect and validate
a profile without asking the operator to write YAML or Java. Its flow through
Phase 3 is:

```text
retained Word source
  -> create versioned DRAFT
  -> label semantic fields and table headers
  -> attach the corrected expected permit
  -> confirm as COLLECTING_EVIDENCE
  -> compile and replay as VALIDATING
  -> CANARY when every retained example passes
```

Use `/api/permit-training-sources/{id}` to obtain the structured document and
its stable cell IDs, then use `/api/permit-training-profiles` to create and edit
the draft. A label records only a supported semantic name, a cell ID or selected
text, and an optional confirmed value. It cannot contain a regular expression
or executable code. Schedule mappings point supported semantic columns to the
header cell IDs returned by the source API.

Before confirmation, the API requires mappings for the source permit number,
permit date, operator ICAO, and the essential schedule columns. It also requires
at least one corrected expected permit and safe business options with
`reviewOnly=true`. Confirmation locks the mapping and starts evidence collection;
it does not affect live mail parsing. Call
`POST /api/permit-training-profiles/{id}/validate` to ask the worker to compile
the definition and replay every corrected example. The compiled artifact uses
only fixed coordinates, plain-text anchors, and column indexes. It is available
for inspection from `GET /api/permit-training-profiles/{id}/compiled`.

If every mapped value matches its corrected example, the version moves to
`CANARY`; otherwise it moves to `NEEDS_REVISION` with per-source diagnostics.
Neither state affects live mail. The existing YAML profiles remain the runtime
baseline until canary evaluation and separate admin activation are implemented.

Make recognition patterns tolerant of harmless punctuation and spacing changes:

```yaml
detectionPatterns:
  - '(?iu)SCHEDULES?\s*[:：]?\s*UTC\s+TIME'
  - '(?iu)HANOI\s*[,，]?\s*'
```

For example, `\s*[:：]?\s*` accepts an optional ASCII or full-width colon with optional surrounding whitespace.

Detection is implemented in:

```text
aerosync-worker/src/main/java/vatm/aerosync/worker/pipeline/WordPermitFormatDetector.java
```

## 2. Configure the permit identity

The `permit` section extracts the source permit number and creates its normalized ATFM identity:

```yaml
permit:
  pattern: '(?iu)\bOF\s*-\s*(?<number>\d{1,5})/(?<month>\d{1,2})/(?<year>20\d{2})VN\b'
  numberGroup: number
  sourceTemplate: 'OF-{number}/{month}/{year}VN'
  normalizedTemplate: 'O/F {number}/S/CHK/{year}'
  zeroPadGroups:
    number: 5
```

Named regular-expression groups such as `number`, `month`, and `year` can be referenced from the templates.

Use `numberTemplate` when the database permit number must be assembled from multiple groups:

```yaml
permit:
  pattern: '(?iu)\bLD\s*-\s*(?<number>\d{1,5})/(?<version>[A-Z])/(?<season>[SW])/(?<year>20\d{2})VN\b'
  numberGroup: number
  numberTemplate: '{number}{version}'
  sourceTemplate: 'LD-{number}/{version}/{season}/{year}VN'
  normalizedTemplate: 'LD {number}{version}/{season}/CHK/{year}'
  zeroPadGroups:
    number: 4
```

## 3. Configure extracted fields

The shared extractor handles common permit dates, labeled ICAO/IATA codes, and
addresses. A profile can still define exact extraction rules when its format needs
a trusted override or when a field has a special meaning:

```yaml
permitDate:
  source: PARAGRAPH
  pattern: '(?iu)HANOI\s*[,，]?\s*(?<date>\d{1,2}/\d{1,2}/20\d{2})'
  group: date
  formats: [d/M/uuuu]
  locale: en

operator:
  source: TABLE
  pattern: '(?iu)ICAO\s*(?:CODE)?\s*[:：]\s*(?<value>[A-Z0-9]{3})'
  group: value
  required: true

billingAddress:
  source: TABLE
  pattern: '(?iu)POSTAL\s+ADDRESS\s*[:：]\s*(?<value>[^\n]+)'
  group: value
  required: false

reference:
  source: RAW
  pattern: '(?iu)\(REF\.\s*(?<value>[^)]+)\)'
  group: value
  required: false
```

Supported source values are:

- `RAW`: paragraphs and table text together
- `PARAGRAPH`: paragraph text only
- `TABLE`: table text only

If a field does not always exist, set `required: false`.

When both paths find a value, a successful profile-specific text rule is treated
as the trusted override. If that rule does not match a harmless layout variant,
the shared semantic evidence remains available. Permit identities are ranked
semantically first so that an older permit cited in a table cannot replace the
main permit number from the document header.

## 4. Configure master defaults and purpose

The `master` section defines values stored for the permit:

```yaml
master:
  authorId: CHK
  permitType: O/F
  version: A
  season: S
  validHours: 72
  flightType: NO
```

When purpose varies by document content, configure a default and mappings:

```yaml
purpose:
  defaultId: PAX
  mappings:
    - pattern: '(?iu)\bCARGO\b|\bFREIGHTER\b'
      value: CAR
    - pattern: '(?iu)\bFERR?Y\b'
      value: FER
    - pattern: '(?iu)\bBUSINESS\b'
      value: BUS
```

## 5. Map the schedule table

The `schedule` section tells the parser how to find and interpret the flight schedule:

```yaml
schedule:
  columns:
    flightNumber: [Flight number, Flt number]
    effectiveFrom: [Effective from, Eff from]
    effectiveTo: [Effective to, Eff to]
    serviceDays: [Days of services, Day(s) of services]
    fromAirport: [Departure Airport, Dep airport]
    etd: [ETD]
    toAirport: [Arrival Airport, Arr airport]
    eta: [ETA]
    aircraftType: [Aircraft Type]
    originalPermit: [Original permit]
    remark: [Notes, Note]

  requiredColumns:
    - flightNumber
    - effectiveFrom
    - effectiveTo
    - serviceDays
    - fromAirport
    - etd
    - toAirport

  excludeColumns: []
  tableContextPatterns: []
  preferredTableContextPatterns: []
  supplementalTableContextPatterns: []
  dateFormats: [dMMMyy, d-MMM-yy, d/M/uuuu]
  timeFormats: [HHmm, H:mm, HH:mm]
  locale: en
  purposeId: PAX
  includeEta: true
  lastMatchingTable: false
  inferIataPrefix: true
```

Column aliases are matched after removing differences in case, accents, punctuation, whitespace, and trailing footnote numbers. The parser can combine up to three header rows and can conservatively match minor spelling differences; those adaptive matches are always marked for operator review.

Shared aliases live in:

```text
aerosync-worker/src/main/resources/permit-reference/permit-semantic-aliases.yaml
```

Alias precedence is profile, then `family`, then global. Add ordinary wording variants to the shared file instead of copying them into every profile. A profile-specific alias remains the trusted exact match; a family/global/fuzzy alias produces diagnostics and requires review until promoted into the profile.

### Selecting among similar tables

For the common CAAV revision structure, the shared extractor already understands
sections 2.1, 2.2, and 2.5. It selects 2.2 as the active replacement schedule,
excludes 2.1, and appends 2.5. A semantic selection is recorded in diagnostics
and sent for operator review.

Use `tableContextPatterns` only when the profile needs a layout-specific override
that the shared roles cannot express:

```yaml
tableContextPatterns:
  - '(?iu)2\.2\.?\s*NEW\s+SCHEDULE'
```

Use `preferredTableContextPatterns` when the parser should prefer a particular table but may fall back to another schedule table:

```yaml
preferredTableContextPatterns:
  - '(?iu)2\.2\.?\s*NEW\s+SCHEDULE'
```

Use `supplementalTableContextPatterns` when rows from additional schedule tables must also be imported:

```yaml
supplementalTableContextPatterns:
  - '(?iu)2\.5\.?\s*TRANSFER\s+FLIGHTS'
```

Other selection controls:

- `excludeColumns` rejects tables containing particular mapped columns.
- `lastMatchingTable: true` selects the last matching table instead of the first.
- `includeEta` determines whether arrival time is imported.
- `inferIataPrefix` enables conversion of IATA-prefixed flight numbers using the detected ICAO operator.

Schedule parsing is implemented in:

```text
aerosync-worker/src/main/java/vatm/aerosync/worker/pipeline/DocxSchedulePermitParser.java
```

## 6. Configure routes

Configure a route table when the document provides sectors and airways:

```yaml
route:
  columns:
    sector: [Sector]
    airways: [Airways]
  requiredColumns: [sector, airways]
  staticAirways: {}
  tableRequired: false
  allowEmpty: true
  fallbackToFirst: false
  lastMatchingTable: false
  filterSchedule: false
```

Relevant controls:

- `tableRequired` rejects the document if the route table is absent.
- `allowEmpty` permits an empty airway value.
- `fallbackToFirst` uses the first route when no sector-specific route matches.
- `filterSchedule` excludes schedule rows that do not have a matching route.
- `staticAirways` can provide fixed route-to-airway mappings when the document has no suitable route table.

Example static mapping:

```yaml
staticAirways:
  'VVTS-WSSS': 'DCT/M768'
```

## 7. Configure aircraft extraction

The aircraft type can come from the schedule, an auxiliary table, or a default:

```yaml
aircraft:
  scheduleColumn: aircraftType
  auxiliaryColumns:
    aircraftType: [Aircraft Type]
    registrationMarks: [Registration Mark, Registration Marks]
  auxiliaryRequiredColumns: [aircraftType, registrationMarks]
  auxiliaryTypeColumn: aircraftType
  remarkPrefix: ''
  defaultType: A320
  lastMatchingTable: false
```

At least one aircraft source must be configured:

- `scheduleColumn`
- A valid `auxiliaryTypeColumn` with `auxiliaryColumns`
- `defaultType`

Known aircraft types are resolved using reference data under:

```text
aerosync-worker/src/main/resources/permit-reference
```

## 8. Configure validation safety

```yaml
validation:
  allowIataAirports: false
  reviewOnly: true
```

Use `reviewOnly: true` initially for a new format and for revision permits. This lets AeroSync parse and validate the permit without treating it as a normal automatic insert.

Set `allowIataAirports: true` only when the target workflow intentionally accepts three-letter airport codes.

## 9. Add a regression test

Add a test under:

```text
aerosync-worker/src/test/java/vatm/aerosync/worker/pipeline
```

Useful examples include:

```text
CaavEnglishIssuedPermitRevisionProfileTest.java
DocxSchedulePermitParserTest.java
Spa066PermitProfileRegressionTest.java
```

The test should verify:

1. The intended profile is selected.
2. The source and normalized permit identities are correct.
3. The permit date, operator, address, and reference are correct.
4. The correct schedule table is selected.
5. Flight numbers, days, airports, times, aircraft, routes, and purpose are correct.
6. Original or superseded schedule tables are excluded when necessary.
7. Similar existing profiles are not selected accidentally.
8. Harmless spacing and punctuation variations still parse.

Run a focused worker test from the repository root:

```powershell
.\aerosync-worker\mvnw.cmd -f pom.xml test -pl aerosync-worker -am `
  "-Dtest=YourNewProfileTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

Then run the complete worker test suite:

```powershell
.\aerosync-worker\mvnw.cmd -f pom.xml test -pl aerosync-worker -am
```

## 10. Validate a real permit corpus

`WordPermitCorpusRegressionTest` can parse a directory of real Word permits and optionally write their extracted paragraph/table structure to reports.

Example:

```powershell
.\aerosync-worker\mvnw.cmd -f pom.xml test -pl aerosync-worker -am `
  "-Dtest=WordPermitCorpusRegressionTest" `
  "-Dpermit.corpus.dir=C:\source-permits" `
  "-Dpermit.corpus.report.dir=C:\permit-reports" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

The report helps identify:

- The exact text Apache POI extracted
- Table headers and cell values
- The paragraph context preceding each table
- Why a detection or extraction pattern did not match

## When Java changes are required

No profile change is normally needed for:

- Extra spaces or punctuation in a permit number.
- Vietnamese accents, English/Vietnamese label variants, or harmless spelling
  differences.
- One to three table-header rows.
- A familiar section 2.1/2.2/2.5 schedule structure.
- A header alias that belongs in the shared or family alias catalog.

A new YAML profile is sufficient when the `.docx` document uses the concepts
already represented by `DocxPermitFormatProfile` but needs a new permit family,
normalization rule, default, or validation policy, including:

- Permit identity
- Permit date and text fields
- Schedule tables
- Route tables or static routes
- Aircraft types
- Existing validation behavior

Java changes are required when the new format introduces a structure that the profile model cannot describe, such as:

- The header is not the first row of a table.
- A schedule spans merged or nested tables in an unsupported way.
- Values must be calculated using new business rules.
- A field is stored in an unsupported source.
- The document is a legacy `.doc` file processed through the normal pipeline.

The profile schema is defined in:

```text
aerosync-worker/src/main/java/vatm/aerosync/worker/pipeline/DocxPermitFormatProfile.java
```

Profile validation is defined in:

```text
aerosync-worker/src/main/java/vatm/aerosync/worker/pipeline/DocxPermitProfileCatalog.java
```

## Legacy DOC limitation

The normal processing pipeline currently dispatches formats differently:

- `.docx` files use `DocxSchedulePermitParser` and YAML profiles.
- `.doc` files use `LegacyDocRevisionPermitParser`.

This dispatch is defined in:

```text
aerosync-worker/src/main/java/vatm/aerosync/worker/pipeline/ParserStep.java
```

Therefore, a genuinely new `.doc` layout may require changes to `LegacyDocRevisionPermitParser` or a pipeline change that routes legacy documents through the profile-based parser.

## Recommended workflow

1. Obtain one or more representative real documents.
2. Run the corpus report to inspect extracted paragraphs, tables, and contexts.
3. Test the document with the shared extractor and the closest existing profile.
4. If only a common header label is missing, add it to the shared or family alias
   catalog rather than creating a profile.
5. Create a profile only for a new permit family or different business policy.
6. Change Java only for a structure or rule the semantic/profile model cannot
   represent.
7. Start genuinely new profiles with `reviewOnly: true`.
8. Add a synthetic regression test and a real-document regression test.
9. Run the full worker test suite to detect profile collisions.
10. Enable normal processing only after reviewing the parsed output.
