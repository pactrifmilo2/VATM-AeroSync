package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.atfm.AtfmRevisionBaseline;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class RevisionReconciliationStep {

    private final AtfmScheduleGateway atfmScheduleGateway;
    private final AirportCodeCatalog airportCodeCatalog;

    @Autowired
    public RevisionReconciliationStep(AtfmScheduleGateway atfmScheduleGateway,
                                      AirportCodeCatalog airportCodeCatalog) {
        this.atfmScheduleGateway = atfmScheduleGateway;
        this.airportCodeCatalog = airportCodeCatalog;
    }

    RevisionReconciliationStep(AtfmScheduleGateway atfmScheduleGateway) {
        this(atfmScheduleGateway, new AirportCodeCatalog());
    }

    public void reconcile(ProcessingContext context) {
        SchedulePermit permit = context.getSchedulePermit();
        if (permit == null || !permit.revision()) {
            return;
        }

        AtfmRevisionBaseline baseline = atfmScheduleGateway.findRevisionBaseline(permit)
                .orElseThrow(() -> new BusinessRuleException(
                        "BR-REVISION-BASE-NOT-FOUND",
                        "Không tìm thấy phép bay gốc trong ATFM: " + permit.normalizedPermitId()));
        List<ScheduleFlight> unchanged = new ArrayList<>(baseline.flights());
        List<ScheduleFlight> reconciled = new ArrayList<>(permit.flights().size());
        List<ScheduleFlight> matchedDeclaredOriginals = new ArrayList<>();
        for (int index = 0; index < permit.originalFlights().size(); index++) {
            ScheduleFlight matched = matchDeclaredOriginal(
                    permit.originalFlights().get(index), unchanged, index + 1);
            matchedDeclaredOriginals.add(matched);
            unchanged.remove(matched);
        }
        for (int index = 0; index < permit.flights().size(); index++) {
            ScheduleFlight revision = permit.flights().get(index);
            ScheduleFlight original;
            if (matchedDeclaredOriginals.size() == permit.flights().size()) {
                original = matchedDeclaredOriginals.get(index);
            } else if (matchedDeclaredOriginals.size() == 1) {
                original = matchedDeclaredOriginals.getFirst();
            } else if (hasSourceAircraft(revision)) {
                original = revision;
            } else if (!matchedDeclaredOriginals.isEmpty()) {
                throw new BusinessRuleException(
                        "BR-REVISION-ROW-PAIR-AMBIGUOUS",
                        "Khong xac dinh duoc dong lich bay goc tuong ung voi dong sua doi %d"
                                .formatted(index + 1));
            } else {
                original = matchOriginal(revision, unchanged, index + 1);
            }
            boolean sameRoute = sameAirport(revision.fromAirport(), original.fromAirport())
                    && sameAirport(revision.toAirport(), original.toAirport());
            long craftId = hasSourceAircraft(revision) ? revision.craftId() : original.craftId();
            BigDecimal mtow = hasSourceAircraft(revision) ? revision.mtow() : original.mtow();
            reconciled.add(revision.withRevisionDefaults(
                    craftId,
                    mtow,
                    original.registration(),
                    sameRoute ? original.via() : null,
                    original.remark()));
        }
        // ATFM keeps its original detail rows. Only the reconciled replacement rows
        // are passed to the writer, which appends them to the existing permit.
        context.setSchedulePermit(permit.withFlights(reconciled));
    }

    private ScheduleFlight matchDeclaredOriginal(ScheduleFlight declared,
                                                  List<ScheduleFlight> baseline,
                                                  int rowNumber) {
        List<ScheduleFlight> matches = baseline.stream()
                .filter(candidate -> same(candidate.flightNumber(), declared.flightNumber()))
                .filter(candidate -> sameAirport(candidate.fromAirport(), declared.fromAirport()))
                .filter(candidate -> sameAirport(candidate.toAirport(), declared.toAirport()))
                .filter(candidate -> !declared.beginDate().isBefore(candidate.beginDate()))
                .filter(candidate -> !declared.endDate().isAfter(candidate.endDate()))
                .filter(candidate -> supportsServiceDays(candidate, declared))
                .filter(candidate -> blank(declared.etd()) || same(candidate.etd(), declared.etd()))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        throw new BusinessRuleException(
                matches.isEmpty()
                        ? "BR-REVISION-BASE-ROW-NOT-FOUND"
                        : "BR-REVISION-BASE-AMBIGUOUS",
                "Khong doi chieu duoc duy nhat dong lich bay goc %d (%s %s-%s, %s, ETD %s, days %s) voi ATFM; ung vien=%s"
                        .formatted(rowNumber, declared.flightNumber(), declared.fromAirport(),
                                declared.toAirport(), declared.beginDate(), declared.etd(),
                                declared.serviceDays(), baseline.stream()
                                        .filter(candidate -> same(candidate.flightNumber(), declared.flightNumber()))
                                        .limit(5)
                                        .map(candidate -> "%s-%s %s..%s ETD %s days %s".formatted(
                                                candidate.fromAirport(), candidate.toAirport(),
                                                candidate.beginDate(), candidate.endDate(),
                                                candidate.etd(), candidate.serviceDays()))
                                        .toList()));
    }

    private ScheduleFlight matchOriginal(ScheduleFlight revision,
                                         List<ScheduleFlight> originals,
                                         int rowNumber) {
        List<ScheduleFlight> flightNumberMatches = originals.stream()
                .filter(candidate -> same(candidate.flightNumber(), revision.flightNumber()))
                .toList();
        if (!flightNumberMatches.isEmpty()) {
            List<ScheduleFlight> routeMatches = flightNumberMatches.stream()
                    .filter(candidate -> sameAirport(candidate.fromAirport(), revision.fromAirport())
                            && sameAirport(candidate.toAirport(), revision.toAirport()))
                    .toList();
            ScheduleFlight selected = selectSafely(
                    routeMatches.isEmpty() ? flightNumberMatches : routeMatches,
                    revision);
            if (selected != null) {
                return selected;
            }
            throw ambiguous(rowNumber, revision);
        }

        List<ScheduleFlight> routeMatches = originals.stream()
                .filter(candidate -> sameAirport(candidate.fromAirport(), revision.fromAirport())
                        && sameAirport(candidate.toAirport(), revision.toAirport()))
                .toList();
        ScheduleFlight selected = selectSafely(routeMatches, revision);
        if (selected != null) {
            return selected;
        }
        if (originals.size() == 1) {
            return originals.getFirst();
        }
        throw ambiguous(rowNumber, revision);
    }

    private ScheduleFlight selectSafely(List<ScheduleFlight> candidates, ScheduleFlight revision) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        List<ScheduleFlight> exactDates = candidates.stream()
                .filter(candidate -> candidate.beginDate().equals(revision.beginDate())
                        && candidate.endDate().equals(revision.endDate()))
                .toList();
        if (exactDates.size() == 1) {
            return exactDates.getFirst();
        }
        List<ScheduleFlight> coveringDates = candidates.stream()
                .filter(candidate -> !revision.beginDate().isBefore(candidate.beginDate())
                        && !revision.endDate().isAfter(candidate.endDate()))
                .toList();
        if (coveringDates.size() == 1) {
            return coveringDates.getFirst();
        }
        List<ScheduleFlight> comparable = !exactDates.isEmpty()
                ? exactDates
                : (!coveringDates.isEmpty() ? coveringDates : candidates);
        if (comparable.size() > 1) {
            long closestDays = comparable.stream()
                    .mapToLong(candidate -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                            candidate.beginDate(), revision.beginDate())))
                    .min()
                    .orElse(Long.MAX_VALUE);
            List<ScheduleFlight> closest = comparable.stream()
                    .filter(candidate -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                            candidate.beginDate(), revision.beginDate())) == closestDays)
                    .toList();
            if (closest.size() == 1) {
                return closest.getFirst();
            }
            comparable = closest;
        }
        ScheduleFlight first = comparable.getFirst();
        return comparable.stream().allMatch(candidate -> sameDefaults(first, candidate))
                ? first
                : null;
    }

    private boolean sameDefaults(ScheduleFlight left, ScheduleFlight right) {
        return left.craftId() == right.craftId()
                && left.mtow().compareTo(right.mtow()) == 0
                && same(left.via(), right.via());
    }

    private BusinessRuleException ambiguous(int rowNumber, ScheduleFlight flight) {
        return new BusinessRuleException(
                "BR-REVISION-BASE-AMBIGUOUS",
                "Không xác định duy nhất dòng phép gốc trong ATFM cho dòng revision %d (%s %s-%s)"
                        .formatted(rowNumber, flight.flightNumber(),
                                flight.fromAirport(), flight.toAirport()));
    }

    private boolean hasSourceAircraft(ScheduleFlight flight) {
        return flight.craftId() > 0
                || (flight.sourceAircraftType() != null && !flight.sourceAircraftType().isBlank());
    }

    private boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private boolean sameAirport(String left, String right) {
        return airportCodeCatalog.normalize(left).equals(airportCodeCatalog.normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean supportsServiceDays(ScheduleFlight baseline, ScheduleFlight declared) {
        if (blank(declared.serviceDays()) || blank(baseline.serviceDays())
                || declared.serviceDays().length() != 7 || baseline.serviceDays().length() != 7) {
            return true;
        }
        for (int index = 0; index < 7; index++) {
            if (declared.serviceDays().charAt(index) != '0'
                    && baseline.serviceDays().charAt(index) == '0') {
                return false;
            }
        }
        return true;
    }
}
