package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.atfm.AtfmRevisionBaseline;
import vatm.aerosync.worker.atfm.AtfmReferenceDataException;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the replacement schedule selected by the parser intact.
 *
 * A revision document may contain both an old/original schedule and a new
 * schedule.  The parser stores the latter in {@code flights} and the former in
 * {@code originalFlights}; only {@code flights} is sent to the writer.  Older
 * code tried to match every original row against ATFM before writing, which
 * incorrectly quarantined valid revisions when the base permit was absent or
 * formatted differently.  Revisions are now written directly: an existing
 * permit receives only the new rows, and a missing target is inserted as a new
 * permit by the coordinator.
 */
@Component
public class RevisionReconciliationStep {

    private static final Pattern FILE_REVISION_NUMBER = Pattern.compile(
            "(?iu)(?:REV(?:ISION)?|RVS|(?<![A-Z0-9])R)\\s*[(_.-]?\\s*(?<number>\\d+)\\s*[)]?");
    private static final Pattern FILE_SEASONAL_REVISION_PREFIX = Pattern.compile(
            "(?iu)(?<![A-Z0-9])R\\d{1,5}[A-Z](?<number>\\d+)(?=$|[^A-Z0-9])");

    private final AtfmScheduleGateway gateway;
    private final AirportCodeCatalog airportCodeCatalog;
    private final PermitOperatorCatalog operatorCatalog;

    /** Kept in the constructor for compatibility with existing wiring/tests. */
    @Autowired
    public RevisionReconciliationStep(AtfmScheduleGateway gateway,
                                      AirportCodeCatalog airportCodeCatalog,
                                      PermitOperatorCatalog operatorCatalog) {
        this.gateway = gateway;
        this.airportCodeCatalog = airportCodeCatalog;
        this.operatorCatalog = operatorCatalog;
    }

    public RevisionReconciliationStep(AtfmScheduleGateway ignoredGateway) {
        this(ignoredGateway, new AirportCodeCatalog(), new PermitOperatorCatalog());
    }

    public RevisionReconciliationStep(AtfmScheduleGateway gateway,
                                      AirportCodeCatalog airportCodeCatalog) {
        this(gateway, airportCodeCatalog, new PermitOperatorCatalog());
    }

    public void reconcile(ProcessingContext context) {
        SchedulePermit permit = context.getSchedulePermit();
        // asSeasonalRevision already materialized OLD + NEW. Reconciliation is
        // repeatable on a context: never treat its cancellation rows as NEW.
        if (permit != null && permit.revisionCreatesNewPermit()) {
            return;
        }
        if (permit != null && permit.referencedSeasonNewPermit()) {
            String referencedPermitId = permit.referencedPermitId();
            java.util.Optional<String> referencedSeason = gateway.findReferencedSeason(permit);
            if (referencedSeason.isEmpty()) {
                throw new AtfmReferenceDataException(
                        "Referenced seasonal permit not found or has no valid season: "
                                + referencedPermitId);
            }
            String inheritedSeason = referencedSeason.get();
            context.setSchedulePermit(permit.withReferencedSeason(inheritedSeason));
            return;
        }
        if (permit != null && permit.implicitTableRevision()) {
            int revisionNumber = revisionNumberFromFile(context.getOriginalFileName());
            if (revisionNumber < 1) {
                revisionNumber = firstAvailableRevisionNumber(gateway.findUsedRevisionNumbers(permit));
            }
            permit = permit.withRevisionNumber(revisionNumber);
            context.setSchedulePermit(permit);
        }
        if (permit != null && permit.importRevision()) {
            AtfmRevisionBaseline baselinePermit = gateway.findRevisionBaseline(permit).orElse(null);
            if (baselinePermit != null && baselinePermit.scheduledStorage()
                    && !permit.usesScheduledStorage()) {
                permit = permit.withExistingScheduledTarget(baselinePermit.scheduledPermitId());
                context.setSchedulePermit(permit);
            }
            List<ScheduleFlight> baseline = baselinePermit == null
                    ? List.of()
                    : baselinePermit.flights();
            String inheritedOperator = baselinePermit == null ? null : baselinePermit.operatorId();
            if ((permit.operatorId() == null || "PRV".equalsIgnoreCase(permit.operatorId()))
                    && inheritedOperator != null && inheritedOperator.matches("(?i)[A-Z0-9]{3}")) {
                permit = permit.withOperatorId(inheritedOperator.toUpperCase(java.util.Locale.ROOT));
            }
            List<ScheduleFlight> reconciled = new ArrayList<>(permit.flights().size());
            for (int index = 0; index < permit.flights().size(); index++) {
                ScheduleFlight current = permit.flights().get(index);
                current = current.withFlightNumber(operatorCatalog.normalizeFlightNumber(
                        current.flightNumber(), permit.operatorId()));
                ScheduleFlight documentedOriginal = findMatchingFlight(
                        permit.originalFlights(), current);
                if (documentedOriginal == null) {
                    documentedOriginal = findUniqueByCallsign(permit.originalFlights(), current);
                }
                if (documentedOriginal == null
                        && permit.originalFlights().size() == permit.flights().size()) {
                    // OLD/NEW tables are paired by position only after exact
                    // identity and unique callsign matching have failed.
                    documentedOriginal = permit.originalFlights().get(index);
                }
                if (!permit.seasonalPermit() && !permit.originalFlights().isEmpty()
                        && documentedOriginal == null) {
                    throw new BusinessRuleException(
                            "BR-REVISION-ROW-MAPPING-AMBIGUOUS",
                            "Cannot identify the original schedule row for revised flight "
                                    + current.flightNumber()
                                    + "; OLD and NEW tables do not form an unambiguous pair");
                }
                ScheduleFlight baselineMatch = findBaseline(
                        baseline, documentedOriginal, current);
                ScheduleFlight inherited = permit.otherDetailsUnchanged()
                        ? baselineMatch
                        : null;
                if (documentedOriginal != null) {
                    current = inheritDetails(current, documentedOriginal);
                }
                if (inherited != null) {
                    current = inheritDetails(current, inherited);
                }
                // The baseline match may have been located through the OLD
                // row's route, so only inherit its VIA when the revised row
                // still flies the exact same airport pair - then the stored
                // VIA applies verbatim, even without an explicit "other
                // details unchanged" statement.
                if (baselineMatch != null && (current.via() == null || current.via().isBlank())
                        && baselineMatch.via() != null && !baselineMatch.via().isBlank()
                        && sameAirport(baselineMatch.fromAirport(), current.fromAirport())
                        && sameAirport(baselineMatch.toAirport(), current.toAirport())) {
                    current = current.withResolvedRoute(
                            current.fromAirport(), current.toAirport(), baselineMatch.via());
                }
                if (shouldInheritAircraft(permit, current, documentedOriginal)) {
                    ScheduleFlight aircraftSource = findUniqueByCallsign(
                            permit.originalFlights(), current);
                    aircraftSource = usableAircraftSource(aircraftSource);
                    if (aircraftSource == null) {
                        aircraftSource = findUniqueByCallsign(baseline, current);
                        aircraftSource = usableAircraftSource(aircraftSource);
                    }
                    if (aircraftSource == null) {
                        aircraftSource = findUniqueByCallsign(baseline, documentedOriginal);
                        aircraftSource = usableAircraftSource(aircraftSource);
                    }
                    if (aircraftSource == null) {
                        aircraftSource = findConsistentAircraftByCallsign(baseline, current);
                    }
                    if (aircraftSource == null) {
                        aircraftSource = findConsistentAircraftByCallsign(
                                baseline, documentedOriginal);
                    }
                    if (aircraftSource == null) {
                        aircraftSource = documentedOriginal;
                    }
                    if (aircraftSource == null
                            || (aircraftSource.craftId() <= 0
                                && (aircraftSource.sourceAircraftType() == null
                                    || aircraftSource.sourceAircraftType().isBlank()))) {
                        aircraftSource = baselineMatch;
                    }
                    if (aircraftSource != null
                            && (aircraftSource.craftId() > 0
                                    || (aircraftSource.sourceAircraftType() != null
                                        && !aircraftSource.sourceAircraftType().isBlank()))) {
                        current = current.withInheritedAircraft(
                                aircraftSource.craftId(), aircraftSource.mtow(),
                                aircraftSource.registration(),
                                aircraftSource.sourceAircraftType());
                    }
                }
                ScheduleFlight resolved = current;
                // A callsign can legitimately occur on multiple dates. The exact
                // baseline row (callsign + date + route) is the authoritative match;
                // do not suppress the revision remark merely because the callsign
                // is repeated elsewhere in the original permit.
                boolean updatesExistingNonScheduled = !permit.seasonalPermit()
                        && (baselineMatch != null || hasUniqueCallsign(baseline, resolved));
                if (updatesExistingNonScheduled) {
                    reconciled.add(resolved.withRemark(permit.revisionRemark()));
                } else if (!permit.seasonalPermit() && documentedOriginal != null
                        && resolved.sourceAircraftType() != null
                        && !resolved.sourceAircraftType().isBlank()) {
                    // A non-scheduled replacement with no ATFM baseline can
                    // still inherit aircraft from its OLD row; retain the
                    // aircraft-only remark while avoiding a synthetic PAX tag.
                    reconciled.add(resolved.withRemark(resolved.sourceAircraftType().trim()));
                } else {
                    reconciled.add(resolved.withAircraftOnlyRemark());
                }
            }
            if (permit.seasonalRevision()) {
                List<ScheduleFlight> replacementRows = permit.cancellationRevision()
                        ? List.of()
                        : reconciled;
                context.setSchedulePermit(permit.asSeasonalRevision(
                        materializeRevisionRows(permit, baseline, replacementRows)));
            } else {
                // A non-scheduled revision always targets its original permit.
                // A missing baseline must be reviewed, never materialized as R(n).
                context.setSchedulePermit(permit.withFlights(reconciled));
            }
        }
    }

    private List<ScheduleFlight> materializeRevisionRows(SchedulePermit permit,
                                                          List<ScheduleFlight> baseline,
                                                          List<ScheduleFlight> revisedRows) {
        // Only rows explicitly listed in the Word document may become
        // cancellation rows. The ATFM baseline is enrichment data, not a source
        // from which to copy the whole original permit into the revision.
        List<ScheduleFlight> originalRows = permit.originalFlights();
        List<ScheduleFlight> materialized = new ArrayList<>(originalRows.size() + revisedRows.size());
        for (int index = 0; index < originalRows.size(); index++) {
            ScheduleFlight original = originalRows.get(index).withFlightNumber(
                    operatorCatalog.normalizeFlightNumber(
                            originalRows.get(index).flightNumber(), permit.operatorId()));
            ScheduleFlight inherited = findBaseline(baseline, original, original);
            if (inherited == null) {
                inherited = findUniqueByCallsign(baseline, original);
            }
            if (inherited == null) {
                inherited = findConsistentAircraftByCallsign(baseline, original);
            }
            ScheduleFlight complete = inherited == null
                    ? original
                    : inheritDetailsAndAircraft(original, inherited);
            materialized.add(complete.asCancelledFrom(originalPermitNumber(permit, original, index)));
        }
        materialized.addAll(revisedRows);
        return materialized;
    }

    private boolean sameNonScheduledIdentity(List<ScheduleFlight> revisedRows,
                                              List<ScheduleFlight> baseline) {
        return !revisedRows.isEmpty() && !baseline.isEmpty()
                && revisedRows.stream().allMatch(revised -> baseline.stream()
                        .anyMatch(original -> sameIdentity(original, revised)));
    }

    private boolean sameIdentity(ScheduleFlight original, ScheduleFlight revised) {
        return sameText(original.flightNumber(), revised.flightNumber())
                && java.util.Objects.equals(original.beginDate(), revised.beginDate())
                && java.util.Objects.equals(original.endDate(), revised.endDate())
                && sameAirport(original.fromAirport(), revised.fromAirport())
                && sameAirport(original.toAirport(), revised.toAirport());
    }

    private boolean hasUniqueCallsign(List<ScheduleFlight> baseline, ScheduleFlight revised) {
        if (revised.flightNumber() == null || revised.flightNumber().isBlank()) {
            return false;
        }
        return baseline.stream()
                .filter(item -> sameText(item.flightNumber(), revised.flightNumber()))
                .count() == 1;
    }

    private boolean sameAirport(String left, String right) {
        String normalizedLeft = airportCodeCatalog.normalize(left);
        String normalizedRight = airportCodeCatalog.normalize(right);
        return !normalizedLeft.isBlank() && normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    private boolean sameText(String left, String right) {
        String normalizedLeft = left == null ? "" : left.trim();
        String normalizedRight = right == null ? "" : right.trim();
        return normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    private int revisionNumberFromFile(String fileName) {
        String value = fileName == null ? "" : fileName;
        Matcher seasonalPrefix = FILE_SEASONAL_REVISION_PREFIX.matcher(value);
        if (seasonalPrefix.find()) {
            return Integer.parseInt(seasonalPrefix.group("number"));
        }
        Matcher matcher = FILE_REVISION_NUMBER.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group("number")) : 0;
    }

    private int firstAvailableRevisionNumber(Set<Integer> usedNumbers) {
        int candidate = 1;
        while (usedNumbers.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private String originalPermitNumber(SchedulePermit permit,
                                        ScheduleFlight original,
                                        int index) {
        if (original.remark() != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?iu)(?:LD|O\\s*/?\\s*F)\\s*-?\\s*\\d{1,5}"
                            + "(?:\\s*/\\s*[A-Z])?(?:\\s*/\\s*[SW])?"
                            + "(?:\\s*/\\s*(?:19|20)\\d{2})(?:VN)?")
                    .matcher(original.remark());
            if (matcher.find()) {
                return matcher.group().trim();
            }
        }
        if (permit.reference() != null && !permit.reference().isBlank()) {
            String[] references = permit.reference().split("\\s*;\\s*");
            if (index < references.length && !references[index].isBlank()) {
                return references[index].trim();
            }
        }
        return permit.revisionBasePermitId();
    }

    private ScheduleFlight findBaseline(List<ScheduleFlight> baseline,
                                        ScheduleFlight original,
                                        ScheduleFlight current) {
        for (String flightNumber : new String[] {
                original == null ? "" : original.flightNumber(), current.flightNumber()}) {
            if (flightNumber == null || flightNumber.isBlank()) {
                continue;
            }
            ScheduleFlight routeSource = original != null
                    && flightNumber.equalsIgnoreCase(original.flightNumber())
                    ? original
                    : current;
            ScheduleFlight match = baseline.stream()
                    .filter(item -> sameFlightRoute(item, routeSource))
                    .findFirst().orElse(null);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private ScheduleFlight findMatchingFlight(List<ScheduleFlight> candidates,
                                               ScheduleFlight expected) {
        return candidates.stream()
                .filter(candidate -> sameFlightRoute(candidate, expected))
                .findFirst()
                .orElse(null);
    }

    private ScheduleFlight findUniqueByCallsign(List<ScheduleFlight> candidates,
                                                ScheduleFlight expected) {
        if (expected == null || expected.flightNumber() == null
                || expected.flightNumber().isBlank()) {
            return null;
        }
        List<ScheduleFlight> matches = candidates.stream()
                .filter(candidate -> sameText(candidate.flightNumber(), expected.flightNumber()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private ScheduleFlight findConsistentAircraftByCallsign(List<ScheduleFlight> candidates,
                                                             ScheduleFlight expected) {
        if (expected == null || expected.flightNumber() == null
                || expected.flightNumber().isBlank()) {
            return null;
        }
        List<ScheduleFlight> matches = candidates.stream()
                .filter(candidate -> sameText(candidate.flightNumber(), expected.flightNumber()))
                .filter(this::hasAircraft)
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        ScheduleFlight first = matches.getFirst();
        boolean consistent = matches.stream().allMatch(candidate -> sameAircraft(first, candidate));
        return consistent ? first : null;
    }

    private boolean hasAircraft(ScheduleFlight flight) {
        return flight.craftId() > 0
                || (flight.sourceAircraftType() != null
                        && !flight.sourceAircraftType().isBlank());
    }

    private ScheduleFlight usableAircraftSource(ScheduleFlight flight) {
        return flight != null && hasAircraft(flight) ? flight : null;
    }

    private boolean sameAircraft(ScheduleFlight left, ScheduleFlight right) {
        if (left.craftId() > 0 || right.craftId() > 0) {
            return left.craftId() > 0 && left.craftId() == right.craftId();
        }
        return sameText(left.sourceAircraftType(), right.sourceAircraftType());
    }

    private boolean shouldInheritAircraft(SchedulePermit permit,
                                          ScheduleFlight current,
                                          ScheduleFlight documentedOriginal) {
        return !permit.implicitTableRevision()
                && (permit.otherDetailsUnchanged() || documentedOriginal != null)
                && !current.aircraftTablePresent()
                && (current.sourceAircraftType() == null
                        || current.sourceAircraftType().isBlank())
                && current.craftId() <= 0;
    }

    private boolean sameFlightRoute(ScheduleFlight left, ScheduleFlight right) {
        return left != null && right != null
                && sameText(left.flightNumber(), right.flightNumber())
                && java.util.Objects.equals(left.beginDate(), right.beginDate())
                && java.util.Objects.equals(left.endDate(), right.endDate())
                && sameAirport(left.fromAirport(), right.fromAirport())
                && sameAirport(left.toAirport(), right.toAirport());
    }

    private ScheduleFlight inheritDetails(ScheduleFlight current, ScheduleFlight source) {
        return current.withRevisionDefaults(
                source.purposeId(), source.craftId(), source.mtow(), source.registration(),
                source.via(), source.remark(), source.sourceAircraftType(), false);
    }

    private ScheduleFlight inheritDetailsAndAircraft(ScheduleFlight current,
                                                      ScheduleFlight source) {
        return current.withRevisionDefaults(
                source.purposeId(), source.craftId(), source.mtow(), source.registration(),
                source.via(), source.remark(), source.sourceAircraftType(), true);
    }
}
