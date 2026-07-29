# Adding a New Permit Format

This guide explains where to make changes when AeroSync must recognize a new Word permit layout.

## DOCX formats

For a new `.docx` layout, the main change is normally a new YAML profile. Java registration is not required because `DocxPermitProfileCatalog` automatically loads every file matching:

```text
aerosync-worker/src/main/resources/permit-formats/*.yaml
```

Start by copying the existing profile that most closely resembles the new document. For example:

```text
aerosync-worker/src/main/resources/permit-formats/caav-generic-landing-issued.yaml
```

Rename the copy to something descriptive, such as:

```text
caav-english-charter-overflight.yaml
```

## 1. Configure format recognition

The beginning of the profile identifies the format:

```yaml
id: caav-english-charter-overflight
priority: 10

detectionPatterns:
  - '(?iu)\bOVERFLIGHT\s+PERMIT\b'
  - '(?iu)\bCHARTER\s+FLIGHT\b'
  - '(?iu)\bOF\s*-\s*\d+'
```

Current detection behavior:

- Every entry in `detectionPatterns` must match the extracted document text.
- When several profiles match, the profile with the highest `priority` is selected.
- When several matching profiles have the same highest priority, the document is rejected as ambiguous.
- Generic fallback profiles normally have a low priority, such as `-100`.

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

The profile can extract the permit date, operator, billing address, and reference from paragraphs, tables, or the full document:

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

Column aliases are matched after removing differences in case, accents, punctuation, whitespace, and trailing footnote numbers.

### Selecting among similar tables

Use `tableContextPatterns` when a schedule table must have a particular preceding heading:

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

A new YAML profile is sufficient when the new `.docx` document uses the concepts already represented by `DocxPermitFormatProfile`, including:

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
3. Copy the closest existing YAML profile.
4. Give the new format unique, tolerant detection patterns.
5. Configure identity, extraction, schedule, route, aircraft, and validation rules.
6. Start with `reviewOnly: true`.
7. Add a synthetic regression test.
8. Test the real documents.
9. Run the full worker test suite to detect profile collisions.
10. Enable normal processing only after reviewing the parsed output.
