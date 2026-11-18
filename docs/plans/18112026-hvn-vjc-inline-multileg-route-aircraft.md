# Plan - 18112026 - HVN/VJC multi-leg routes and aircraft by route

## Objective

Safely read airway routes and aircraft types from the six supplied HVN/VJC permit documents. Support inline text such as `SGN-HYD-SGN: R468` and aircraft rules scoped to a route, without pairing metadata to schedule rows by their order.

## Scope

- The supplied documents: LD 4281, LD 4282, LD 4289, LD 4300, LD 4302, and LD 4301.
- Airline YAML profiles and only the shared parser changes needed for inline route sequences and aircraft-by-route rules.
- Regression tests using the six files and synthetic coverage for multi-leg, reverse direction, route-specific rules, and fallback.
- No email replay, no writes to Oracle/ATFM, no deployment.

## Initial findings

- **LD 4301 HVN:** `SGN-HYD-SGN: R468; PQC-HYD-PQC: R334`. The schedule contains SGN->HYD, HYD->SGN, PQC->HYD, and HYD->PQC legs. Each round-trip sequence must expand to directed pairs and keep its airway association. Aircraft `321/320` is stated globally.
- **LD 4302 HVN:** Inline directional routes are `HAN-VNS: W21 B214; VNS-HAN: B214 W21`. Preserve the direction-specific airway sequence during normalization.
- **LD 4300 HVN:** Aircraft types are route-specific for `HAN-SGN-HAN: 787/350/321/320`; the stated fallback for other routes is `321/320`. The schedule has many other sectors, so rules must match sector pairs, not row order.
- **LD 4282 VJC:** Schedule tables contain route-heading rows interleaved with flights. A separate table maps sector to airways, and a separate aircraft table lists global aircraft types.
- **LD 4281 VJC:** The revision has OLD/NEW tables and route-heading rows; it has no dedicated aircraft table. Do not apply NEW metadata to OLD or infer a new aircraft type.
- **LD 4289 HVN:** The revision notes inherit route/aircraft information from seasonal permit LD-69/A/W/2026VN, while some schedule sections specify types. Preserve existing baseline/revision behavior; do not turn a reference note into a static rule for every flight.
- The parser supports YAML `staticAirways` and route tables. Current `A-B-C` inference is in the headerless-table path; aircraft auxiliary tables currently aggregate aircraft types globally and do not represent `sector -> types`.

## Proposed assumptions

- A route chain `A-B-C` means directed legs `A->B` and `B->C`; `A-B-A` covers both directions. An airway attached to the chain applies to each leg unless the document states a more specific sector rule.
- A matching route-specific aircraft rule takes precedence over the explicit fallback `other routes`; the global aircraft list is used only when the profile declares it as a fallback. Do not infer pairing by schedule row order.
- For revisions, use route/aircraft evidence from the correct NEW portion only. When NEW omits a field, use the existing baseline behavior; do not infer from OLD.

## Questions requiring user selection

### 1. Aircraft rule precedence

- [ ] A - **Recommended:** Route-specific first, then explicit `other routes` fallback, then global permit-wide list. Pros: preserves the LD 4300 exception. Cons: requires route-keyed parsing and matching.
- [ ] B - Use only one global type list for the whole permit. Pros: simpler. Cons: loses the HAN-SGN-HAN distinction in LD 4300.
- [ ] C - Use only route-specific rules and no fallback. Pros: avoids broad assignment. Cons: leaves unlisted sectors without aircraft despite an explicit `other routes` clause.
- Other option from user: ____________________

### 2. YAML pattern representation

- [ ] A - **Recommended:** Add structured pattern definitions to the HVN profile for inline airway route chains and aircraft-by-route clauses; process them in shared parser logic. Pros: format behavior is visible in YAML and testable across samples. Cons: requires profile schema and parser expansion.
- [ ] B - Hard-code HVN-specific behavior in Java; YAML only detects the document. Pros: less YAML schema work. Cons: format rules become hidden in code and harder to extend.
- [ ] C - Manually add each observed permit route as YAML `staticAirways`. Pros: no parser changes. Cons: does not generalize to future permits and risks mismatch with schedules.
- Other option from user: ____________________

## Default implementation scope if recommendations are selected

1. Extend profile definitions for inline route rules and aircraft-by-route rules; validate required regex capture groups at profile load time.
2. Expand route chains into directed legs and normalize airway text using current behavior; match each schedule row independently.
3. Support `other routes` only as an explicit profile fallback.
4. Parse aircraft lists such as `321/320` and `787/350/321/320` using `AircraftTypeNotation`; never infer aircraft from callsign, filename, or schedule order.
5. Add regression tests for all six DOCX files. Assert LD 4301 maps R468 to SGN<->HYD and R334 to PQC<->HYD; LD 4302 keeps directional airway lists; LD 4300 distinguishes HAN<->SGN from fallback; VJC keeps route-table and revision behavior.
6. Run targeted worker tests and profile corpus regression; record actual results in the changelog.
7. Use documents as read-only fixtures. Do not import or write to live ATFM.

## Waiting for confirmation

No source, YAML, or test files dependent on the choices above have been changed. User can mark choices, provide an alternative, or reply with selections. Record choices here before implementation.