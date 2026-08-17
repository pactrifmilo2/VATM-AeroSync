package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.atfm.AtfmRevisionBaseline;
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
                ScheduleFlight documentedOriginal = findMatchingFlight(
                        permit.originalFlights(), current);
                ScheduleFlight inherited = permit.otherDetailsUnchanged()
                        ? findBaseline(baseline, documentedOriginal, current)
                        : null;
                if (documentedOriginal != null) {
                    current = inherit(current, documentedOriginal);
                }
                if (inherited != null) {
                    current = inherit(current, inherited);
                }
                current = current.withFlightNumber(operatorCatalog.normalizeFlightNumber(
                        current.flightNumber(), permit.operatorId()));
                ScheduleFlight resolved = current;
                boolean updatesExistingNonScheduled = !permit.seasonalPermit()
                        && baseline.stream().anyMatch(item -> sameIdentity(item, resolved));
                reconciled.add(updatesExistingNonScheduled
                        ? resolved.withRemark(permit.revisionReference())
                        : resolved.withAircraftOnlyRemark());
            }
            if (permit.seasonalRevision()) {
                context.setSchedulePermit(permit.asSeasonalRevision(
                        materializeRevisionRows(permit, baseline, reconciled)));
            } else if (!baseline.isEmpty()) {
                context.setSchedulePermit(permit.withFlights(reconciled));
            } else {
                context.setSchedulePermit(permit.asNonScheduledRevision(
                        materializeRevisionRows(permit, baseline, reconciled)));
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
            ScheduleFlight original = originalRows.get(index);
            ScheduleFlight inherited = findBaseline(baseline, original, original);
            ScheduleFlight complete = inherited == null ? original : inherit(original, inherited);
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
        Matcher matcher = FILE_REVISION_NUMBER.matcher(fileName == null ? "" : fileName);
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

    private boolean sameFlightRoute(ScheduleFlight left, ScheduleFlight right) {
        return left != null && right != null
                && sameText(left.flightNumber(), right.flightNumber())
                && java.util.Objects.equals(left.beginDate(), right.beginDate())
                && java.util.Objects.equals(left.endDate(), right.endDate())
                && sameAirport(left.fromAirport(), right.fromAirport())
                && sameAirport(left.toAirport(), right.toAirport());
    }

    private ScheduleFlight inherit(ScheduleFlight current, ScheduleFlight source) {
        return current.withRevisionDefaults(
                source.purposeId(), source.craftId(), source.mtow(), source.registration(),
                source.via(), source.remark());
    }
}
