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
 * Reconciles SC using its existing materialization policy. For NO replacements,
 * OLD identifies the baseline and NEW supplies the schedule; missing attributes
 * inherit from that exact baseline. The gateway atomically deletes OLD/inserts NEW.
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
            if (isMultiDayNonScheduledRevisionCandidate(permit)) {
                validateReferencedScheduledBaseline(permit, baselinePermit);
                if (baselinePermit.multiDayNonScheduledStorage()) {
                    permit = permit.withMultiDayNonScheduledRevisionTarget(
                            baselinePermit.scheduledPermitId());
                    if (baselinePermit.operatorId() != null
                            && !baselinePermit.operatorId().isBlank()) {
                        permit = permit.withOperatorId(baselinePermit.operatorId());
                    }
                    context.setSchedulePermit(permit.withFlights(
                            materializeMultiDayNonScheduledRevision(
                                    permit, baselinePermit.flights())));
                    return;
                }
                permit = permit.withExistingScheduledTarget(
                        baselinePermit.scheduledPermitId());
                context.setSchedulePermit(permit);
            }
            if (baselinePermit != null && baselinePermit.scheduledStorage()
                    && !permit.usesScheduledStorage()) {
                permit = permit.withExistingScheduledTarget(baselinePermit.scheduledPermitId());
                context.setSchedulePermit(permit);
            }
            if (permit.usesScheduledStorage() && context.getLegacyWordParseFailure() != null) {
                throw context.getLegacyWordParseFailure();
            }
            List<ScheduleFlight> baseline = baselinePermit == null
                    ? List.of()
                    : baselinePermit.flights();
            if (permit.cancellationOnlyNotice() && baseline.isEmpty()) {
                throw new BusinessRuleException(
                        "BR-CANCELLATION-BASE-NOT-FOUND",
                        "Cannot cancel seasonal permit without an ATFM baseline: "
                                + permit.revisionBasePermitId());
            }
            if (permit.cancellationOnlyNotice() && permit.originalFlights().isEmpty()) {
                permit = permit.withFlightsAndOriginals(permit.flights(), baseline);
                context.setSchedulePermit(permit);
            }
            String inheritedOperator = baselinePermit == null ? null : baselinePermit.operatorId();
            if ((permit.operatorId() == null || "PRV".equalsIgnoreCase(permit.operatorId()))
                    && inheritedOperator != null && inheritedOperator.matches("(?i)[A-Z0-9]{3}")) {
                permit = permit.withOperatorId(inheritedOperator.toUpperCase(java.util.Locale.ROOT));
            }
            if (permit.replacesNonScheduledRows()) {
                // The master is retained, including its operator. A missing carrier
                // label must not re-infer another airline from two callsign letters.
                if (inheritedOperator != null && !inheritedOperator.isBlank()) {
                    permit = permit.withOperatorId(inheritedOperator);
                }
                // Resolve destination first. Legacy NO permits targeting SC never reparse
                // with the new mapper and retain their old reconciliation behavior.
                if (context.getFilePath() != null && java.nio.file.Files.isRegularFile(context.getFilePath())
                        && context.getFilePath().toString().matches("(?i).*\\.docx?")) {
                    try {
                        SchedulePermit mapped = new DocxSchedulePermitParser().parseNoReplacement(context.getFilePath());
                        if (mapped.usesScheduledStorage()) throw new BusinessRuleException("BR-REVISION-STORE-MISMATCH",
                                "Complete NEW schedule requires SC; refusing NO replacement after partial legacy parsing");
                        permit = permit.withFlightsAndOriginals(mapped.flights(), mapped.originalFlights());
                    } catch (java.io.IOException e) {
                        throw new BusinessRuleException("BR-REVISION-DOCUMENT-READ", "Cannot read OLD/NEW: " + e.getMessage());
                    }
                }
                context.setSchedulePermit(reconcileNoReplacement(permit, baseline));
                context.setNoReplacementMapped(true);
                return;
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
                if (documentedOriginal == null && !permit.seasonalPermit()) {
                    documentedOriginal = findUniqueByStableScheduleFields(
                            permit.originalFlights(), current);
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

    private boolean isMultiDayNonScheduledRevisionCandidate(SchedulePermit permit) {
        return !permit.seasonalPermit() && permit.multiDayNonScheduled();
    }

    private void validateReferencedScheduledBaseline(SchedulePermit permit,
                                                     AtfmRevisionBaseline baseline) {
        boolean explicitReference = permit.referencedPermitId() != null
                && !permit.referencedPermitId().isBlank();
        if (!explicitReference && permit.originalFlights().isEmpty()) {
            throw new BusinessRuleException(
                    "BR-MULTI-DAY-REVISION-REFERENCE-REQUIRED",
                    "Multi-day non-scheduled revision without a referenced permit requires an OLD schedule table");
        }
        if (baseline == null || !baseline.scheduledStorage()) {
            throw new BusinessRuleException(
                    "BR-MULTI-DAY-REVISION-BASE-NOT-FOUND",
                    "Referenced scheduled permit was not found: " + permit.revisionBasePermitId());
        }
        String expectedPermitId = explicitReference
                ? permit.referencedPermitId()
                : permit.revisionBasePermitId();
        if (!sameText(expectedPermitId, baseline.scheduledPermitId())) {
            throw new BusinessRuleException(
                    "BR-MULTI-DAY-REVISION-REFERENCE-MISMATCH",
                    explicitReference
                            ? "Referenced permit does not match the locked scheduled master"
                            : "Revision permit does not match the locked scheduled master");
        }
    }

    private List<ScheduleFlight> materializeMultiDayNonScheduledRevision(
            SchedulePermit permit,
            List<ScheduleFlight> baseline) {
        if (permit.originalFlights().isEmpty()) {
            throw new BusinessRuleException(
                    "BR-MULTI-DAY-REVISION-OLD-REQUIRED",
                    "Multi-day non-scheduled revision requires an OLD schedule table");
        }
        List<ScheduleFlight> oldRows = new ArrayList<>();
        for (ScheduleFlight old : permit.originalFlights()) {
            List<ScheduleFlight> matches = baseline.stream()
                    .filter(candidate -> sameRevisionIdentity(candidate, old))
                    .toList();
            if (matches.size() != 1) {
                throw new BusinessRuleException(
                        "BR-MULTI-DAY-REVISION-OLD-NOT-UNIQUE",
                        "OLD schedule does not match exactly one row in the referenced permit: "
                                + old.flightNumber());
            }
            oldRows.add(inheritDetailsAndAircraft(old, matches.getFirst()));
        }

        List<ScheduleFlight> newRows = new ArrayList<>();
        for (int index = 0; index < permit.flights().size(); index++) {
            ScheduleFlight current = permit.flights().get(index);
            ScheduleFlight documentedOld = findMatchingFlight(permit.originalFlights(), current);
            if (documentedOld == null) {
                documentedOld = findUniqueByCallsign(permit.originalFlights(), current);
            }
            if (documentedOld == null
                    && permit.originalFlights().size() == permit.flights().size()) {
                documentedOld = permit.originalFlights().get(index);
            }
            if (documentedOld == null) {
                throw new BusinessRuleException(
                        "BR-MULTI-DAY-REVISION-ROW-MAPPING-AMBIGUOUS",
                        "Cannot pair NEW with OLD for " + current.flightNumber());
            }
            ScheduleFlight matchedOld = documentedOld;
            ScheduleFlight baselineRow = baseline.stream()
                    .filter(candidate -> sameRevisionIdentity(candidate, matchedOld))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException(
                            "BR-MULTI-DAY-REVISION-OLD-NOT-FOUND",
                            "Referenced OLD schedule disappeared before reconciliation"));
            newRows.add(inheritUnchangedMultiDayFields(
                    permit, current, documentedOld, baselineRow));
        }
        List<ScheduleFlight> materialized = new ArrayList<>(oldRows.size() + newRows.size());
        materialized.addAll(oldRows);
        materialized.addAll(newRows);
        return materialized;
    }

    private ScheduleFlight inheritUnchangedMultiDayFields(SchedulePermit permit,
                                                           ScheduleFlight current,
                                                           ScheduleFlight documentedOld,
                                                           ScheduleFlight baseline) {
        String purpose = PurposeResolutionStep.hasExplicitNewPurpose(permit.rawContent(), current)
                ? current.purposeId() : baseline.purposeId();
        boolean hasNewAircraft = current.craftId() > 0
                || (current.sourceAircraftType() != null
                        && !current.sourceAircraftType().isBlank());
        String via = current.via();
        if ((via == null || via.isBlank())
                && sameAirport(baseline.fromAirport(), current.fromAirport())
                && sameAirport(baseline.toAirport(), current.toAirport())) {
            via = baseline.via();
        }
        if ((via == null || via.isBlank())
                && (!sameAirport(baseline.fromAirport(), current.fromAirport())
                        || !sameAirport(baseline.toAirport(), current.toAirport()))) {
            throw new BusinessRuleException(
                    "BR-MULTI-DAY-REVISION-NEW-VIA-REQUIRED",
                    "Changed sector requires an explicit NEW route: "
                            + current.fromAirport() + " -> " + current.toAirport());
        }
        return new ScheduleFlight(
                purpose,
                hasNewAircraft ? current.craftId() : baseline.craftId(),
                hasNewAircraft ? current.mtow() : baseline.mtow(),
                current.flightNumber(),
                hasNewAircraft ? current.registration() : baseline.registration(),
                current.serviceDays(), current.fromAirport(), current.toAirport(),
                current.etd(), current.eta(), via, current.beginDate(), current.endDate(),
                current.remark(),
                hasNewAircraft ? current.sourceAircraftType() : baseline.sourceAircraftType(),
                current.aircraftTablePresent());
    }

    private boolean sameRevisionIdentity(ScheduleFlight stored, ScheduleFlight old) {
        return sameText(stored.flightNumber(), old.flightNumber())
                && sameAirport(stored.fromAirport(), old.fromAirport())
                && sameAirport(stored.toAirport(), old.toAirport())
                && java.util.Objects.equals(stored.beginDate(), old.beginDate())
                && java.util.Objects.equals(stored.endDate(), old.endDate())
                && sameText(stored.etd(), old.etd())
                && (old.eta() == null || old.eta().isBlank()
                        || sameArrivalTime(stored.eta(), old.eta()));
    }

    private boolean sameArrivalTime(String stored, String documented) {
        return sameText(stripArrivalDayOffset(stored), stripArrivalDayOffset(documented));
    }

    private String stripArrivalDayOffset(String value) {
        return value == null ? "" : value.trim().replaceFirst("\\+$", "");
    }

    private SchedulePermit reconcileNoReplacement(SchedulePermit permit, List<ScheduleFlight> baseline) {
        if (permit.originalFlights().isEmpty()) {
            throw new BusinessRuleException("BR-REVISION-OLD-REQUIRED",
                    "PERMNO replacement requires an OLD schedule to identify rows for deletion");
        }
        if (baseline.isEmpty()) throw new BusinessRuleException("BR-REVISION-BASE-NOT-FOUND",
                "Original PERMNO permit not found: " + permit.atfmTargetPermitId());
        List<ScheduleFlight> originals = permit.originalFlights().stream().map(f -> normalizeNoFlight(f, permit)).toList();
        List<ScheduleFlight> replacements = permit.flights().stream().map(f -> normalizeNoFlight(f, permit)).toList();
        List<ScheduleFlight> result = new ArrayList<>();
        for (int i = 0; i < replacements.size(); i++) {
            ScheduleFlight current = replacements.get(i);
            List<ScheduleFlight> identityMatches = originals.stream()
                    .filter(f -> sameFlightRoute(f, current)).toList();
            ScheduleFlight old = identityMatches.size() == 1 ? identityMatches.getFirst()
                    : findUniqueByCallsign(originals, current);
            if (old == null && originals.size() == 1) old = originals.getFirst();
            // Multiple changed callsigns/dates cannot be paired by row order safely.
            // Inherit only when one explicit OLD can be identified.
            if (old == null) throw new BusinessRuleException("BR-REVISION-ROW-MAPPING-AMBIGUOUS",
                    "Cannot pair NEW with OLD for unchanged fields: " + current.flightNumber());
            final ScheduleFlight documented = old;
            List<ScheduleFlight> matches = baseline.stream().filter(f ->
                    vatm.aerosync.worker.model.NoRevisionIdentity.matches(normalizeNoFlight(f, permit), documented)).toList();
            if (matches.size() > 1) throw new BusinessRuleException("BR-REVISION-OLD-AMBIGUOUS",
                    "More than one stored row matches OLD: " + old.flightNumber());
            // A retry after commit has no OLD row. Use exact NEW only to recover
            // enrichment; the gateway still requires the persisted revision receipt.
            if (matches.isEmpty()) matches = baseline.stream().filter(f ->
                    vatm.aerosync.worker.model.NoRevisionIdentity.matches(normalizeNoFlight(f, permit), current)).toList();
            if (matches.size() != 1) throw new BusinessRuleException("BR-REVISION-OLD-NOT-FOUND",
                    "Cannot uniquely identify baseline OLD (or committed NEW) for " + old.flightNumber());
            ScheduleFlight source = matches.getFirst();
            String purpose = PurposeResolutionStep.hasExplicitNewPurpose(permit.rawContent(), current)
                    ? current.purposeId() : source.purposeId();
            // A present-but-empty Aircraft table is not a CRAFT change. Only
            // an explicit NEW type (or an already resolved NEW craft ID) wins.
            boolean hasNewAircraft = current.craftId() > 0
                    || (current.sourceAircraftType() != null && !current.sourceAircraftType().isBlank());
            String via = current.via();
            if ((via == null || via.isBlank()) && sameAirport(source.fromAirport(), current.fromAirport())
                    && sameAirport(source.toAirport(), current.toAirport())) via = source.via();
            if ((via == null || via.isBlank()) && (!sameAirport(source.fromAirport(), current.fromAirport())
                    || !sameAirport(source.toAirport(), current.toAirport()))) {
                throw new BusinessRuleException("BR-REVISION-NEW-VIA-REQUIRED",
                        "Changed sector requires an explicit NEW route: " + current.fromAirport() + " -> " + current.toAirport());
            }
            result.add(new ScheduleFlight(purpose, hasNewAircraft ? current.craftId() : source.craftId(),
                    hasNewAircraft ? current.mtow() : source.mtow(), current.flightNumber(),
                    hasNewAircraft ? current.registration() : source.registration(), current.serviceDays(),
                    current.fromAirport(), current.toAirport(), current.etd(), current.eta(), via,
                    current.beginDate(), current.endDate(), permit.revisionRemark(),
                    hasNewAircraft ? current.sourceAircraftType() : source.sourceAircraftType(), current.aircraftTablePresent()));
        }
        List<ScheduleFlight> snapshots = originals.stream().map(old -> {
            List<ScheduleFlight> matches = baseline.stream().map(f -> normalizeNoFlight(f, permit))
                    .filter(f -> vatm.aerosync.worker.model.NoRevisionIdentity.matches(f, old)).toList();
            return matches.size() == 1 ? matches.getFirst() : old;
        }).toList();
        return permit.withFlightsAndOriginals(result, snapshots);
    }

    private ScheduleFlight normalizeNoFlight(ScheduleFlight flight, SchedulePermit permit) {
        return flight.withFlightNumber(operatorCatalog.normalizeFlightNumber(flight.flightNumber(), permit.operatorId()))
                .withResolvedRoute(airportCodeCatalog.normalize(flight.fromAirport()),
                        airportCodeCatalog.normalize(flight.toAirport()), flight.via());
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

    /**
     * A non-seasonal revision may change one endpoint while retaining the
     * callsign, effective interval and UTC times. Pair only when those fields
     * identify exactly one OLD row; otherwise the ambiguity guard remains.
     */
    private ScheduleFlight findUniqueByStableScheduleFields(
            List<ScheduleFlight> candidates,
            ScheduleFlight expected) {
        if (expected == null || expected.flightNumber() == null
                || expected.flightNumber().isBlank()) {
            return null;
        }
        List<ScheduleFlight> matches = candidates.stream()
                .filter(candidate -> sameText(candidate.flightNumber(), expected.flightNumber()))
                .filter(candidate -> java.util.Objects.equals(candidate.beginDate(), expected.beginDate()))
                .filter(candidate -> java.util.Objects.equals(candidate.endDate(), expected.endDate()))
                .filter(candidate -> sameText(candidate.etd(), expected.etd()))
                .filter(candidate -> expected.eta() == null || expected.eta().isBlank()
                        || sameText(candidate.eta(), expected.eta()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
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
