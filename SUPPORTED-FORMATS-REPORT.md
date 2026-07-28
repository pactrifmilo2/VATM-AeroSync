# VATM AeroSync Supported Word and Excel Format Report

**Report date:** 27 July 2026  
**Scope:** Formats currently implemented in `aerosync-worker`

## Executive summary

AeroSync currently implements **six Word permit layouts** and **one generic Excel flight-row layout**.

- Word permits are parsed into a permit master plus schedule details and can be written to `T_PERMMASTER_SC` and `T_PERMDETAIL_SC`.
- Excel files are parsed as generic flight rows and written through the normal `FLIGHT_DATA` pipeline. They are not interpreted as permit documents.
- The supplied CAAV multi-row flight-schedule workbook layout is **not implemented yet**.
- Legacy `.xls` workbooks are not supported; the implemented Excel parser accepts `.xlsx`.

## Implemented Word permit formats

| No. | Implemented format | Representative document | Imported section and behavior | Normalized permit example | Verification status |
|---:|---|---|---|---|---|
| 1 | CAAV English scheduled overflight permit | `OF-5199 (1).docx` | Reads scheduled flights, route/airways and aircraft information from the permit tables | `O/F 05199/S/CHK/2026` | Profile and parser tests pass. The currently stored real file is recognized but its internal permit number parses as `OF-5224`, so it does not match the filename/test expectation `OF-5199`. |
| 2 | CAAV English landing/overflight permit revision | `LD-06.A.S.2026VN.REV8 (1).doc` | Imports only **2.2 New schedule(s)**; ignores the original schedule; removes `VN/REV` from the stored identity | `LD-06/A/S/2026` | Synthetic and supplied legacy `.doc` verification pass. |
| 3 | SPA066 Vietnamese non-scheduled landing revision | `(SPA066) REV1 LD-2631 C26_18Jul-18Jul_ND.docx` | Imports only section **2.2 New schedule**; supports passenger flights and IATA airport codes | `LD-2631/07/2026` | Packaged real-document regression test passes. |
| 4 | CAAV Vietnamese landing permit correction | `cor(07feb)_LD-545.02.2025VN-rvs-ld38a-qh1123_14feb.docx` | Imports only **2.2 Lịch bay mới**; maps operator code `QH` to `BAV` | `LD-545/02/2025` | Synthetic and supplied real-document verification pass. |
| 5 | CAAV English overflight permit revision | `OF-5277 (REV1).docx` | Selects the **3.2 New** schedule and does not import the 3.1 old schedule | `O/F 05277/S/CHK/2026` | Synthetic and supplied real-document verification pass. |
| 6 | SPA017 Vietnamese seasonal landing revision | `(SPA017) REV9 LD-32B S26_23JULY-31JUL_ND.docx` | Imports only **2.2 Lịch bay mới (sửa đổi)** and preserves the related permit reference | `LD 0032B/S/CHK/2026` | Profile tests pass. The currently stored real file is recognized but its internal identity parses as `LD-62/B/S/2025VN/REV9`, so it does not match the filename/test expectation. |

### Word data extracted

Depending on the layout, the Word profiles extract:

- Permit number and normalized permit identity
- Permit issue date
- Operator/ICAO code
- Billing or postal address
- Original permit/reference
- Flight number
- Effective-from and effective-to dates
- Days of operation
- Departure airport and ETD
- Arrival airport and ETA, when required
- Route/airways
- Aircraft mapping, purpose, MTOW and remarks

### Word-specific mappings currently configured

| Format | Important configured mapping |
|---|---|
| English landing revision (`LD-06`) | `767F` → `CRAFT_ID 4046`, MTOW `185`, purpose `CAR` |
| SPA066 (`LD-2631`) | `321/32Q/32N` aliases → `CRAFT_ID 6021`, MTOW `89`, purpose `PAX` |
| Vietnamese landing correction (`LD-545`) | `320/32Q/32N/321` aliases → `CRAFT_ID 10`, purpose `PAX` |
| SPA017 seasonal revision | Default `CRAFT_ID 4366`, purpose `PAX` |
| English scheduled overflight | Default `CRAFT_ID 1935`, purpose `CAR` |
| English overflight revision (`OF-5277`) | Default `CRAFT_ID 45`, purpose `CHT` |

## Implemented Excel format

### Generic `.xlsx` flight-row format

The implemented Excel parser reads the **first worksheet**, with the header in the **first row**.

Required columns:

| Column | Meaning |
|---|---|
| `callsign` | Flight callsign |
| `from` | Departure airport |
| `to` | Arrival airport |
| `dateflight` | Flight date |

Example:

| callsign | from | to | dateflight |
|---|---|---|---|
| VN123 | HAN | SGN | 2026-07-27 |

Supported date cells:

- Native Excel date cells
- ISO text dates in `yyyy-MM-dd` format

Rows with an empty callsign are ignored. Automated tests confirm native Excel dates and locale-formatted Excel date cells can be read.

## Formats not yet implemented

The following should not be described as supported:

- CAAV operational flight-schedule workbooks such as `FLIGHT SCHEDULE ON 22-JUL-26_CAAV.xlsx`
- Multi-row or merged-header Excel schedule layouts
- Legacy Excel `.xls`
- Arbitrary Word permits that do not match one of the six configured profiles
- PDF permit import
- Image attachment import

These files are either skipped as unsupported or moved to the dated error/quarantine location with a reason.

## Database behavior

When `APP_ATFM_WRITE_ENABLED=true`, a successfully validated Word permit is handled as one target transaction:

1. Insert the permit master into `T_PERMMASTER_SC`.
2. Obtain the generated `PERM_ID`.
3. Insert all selected schedule rows into `T_PERMDETAIL_SC`.
4. Record the import outcome in the AeroSync tracking tables.

Exact duplicate content is intended to be skipped. A changed schedule for the same normalized permit is held for manual revision review instead of silently overwriting the existing permit.

## Verification performed

The focused automated suite executed parser tests for all six Word profiles and the generic Excel layout:

- Baseline focused suite: **17 tests executed, 0 failures**; five optional real-file checks were skipped until explicit paths were supplied.
- Explicit supplied-file verification: all five configured files were detected and parsed; **9 of 11 assertions passed**. The two failures were identity mismatches between the stored document contents and the filenames/hard-coded expected values, as noted in the table above.
- The SPA066 packaged real-document regression test passed separately.

This confirms the listed layouts are implemented, while also identifying the two sample-identity discrepancies that should be resolved before those files are used as fixed acceptance-test fixtures.
