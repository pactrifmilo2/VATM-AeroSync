package vatm.aerosync.worker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ScheduleFlight(
        String purposeId,
        long craftId,
        BigDecimal mtow,
        String flightNumber,
        String registration,
        String serviceDays,
        String fromAirport,
        String toAirport,
        String etd,
        String eta,
        String via,
        LocalDate beginDate,
        LocalDate endDate,
        String remark,
        String sourceAircraftType,
        boolean aircraftTablePresent
) {

    public ScheduleFlight(
            String purposeId,
            long craftId,
            BigDecimal mtow,
            String flightNumber,
            String registration,
            String serviceDays,
            String fromAirport,
            String toAirport,
            String etd,
            String eta,
            String via,
            LocalDate beginDate,
            LocalDate endDate,
            String remark
    ) {
        this(purposeId, craftId, mtow, flightNumber, registration, serviceDays,
                fromAirport, toAirport, etd, eta, via, beginDate, endDate, remark, null, false);
    }

    public ScheduleFlight(
            String purposeId, long craftId, BigDecimal mtow, String flightNumber,
            String registration, String serviceDays, String fromAirport, String toAirport,
            String etd, String eta, String via, LocalDate beginDate, LocalDate endDate,
            String remark, String sourceAircraftType) {
        this(purposeId, craftId, mtow, flightNumber, registration, serviceDays,
                fromAirport, toAirport, etd, eta, via, beginDate, endDate, remark,
                sourceAircraftType, false);
    }

    public ScheduleFlight withResolvedAircraft(long resolvedCraftId, BigDecimal resolvedMtow) {
        return new ScheduleFlight(
                purposeId, resolvedCraftId, resolvedMtow, flightNumber, registration,
                serviceDays, fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                remark, sourceAircraftType, aircraftTablePresent);
    }

    public ScheduleFlight withSourceAircraftType(String normalizedSourceAircraftType) {
        String normalizedRemark = remark;
        String source = sourceAircraftType == null ? "" : sourceAircraftType.trim();
        String normalized = normalizedSourceAircraftType == null
                ? ""
                : normalizedSourceAircraftType.trim();
        if (!source.isBlank() && !source.equals(normalized) && remark != null) {
            String trimmedRemark = remark.trim();
            if (trimmedRemark.equalsIgnoreCase(source)) {
                normalizedRemark = normalized;
            } else if (trimmedRemark.toUpperCase(java.util.Locale.ROOT)
                    .endsWith(" " + source.toUpperCase(java.util.Locale.ROOT))) {
                normalizedRemark = trimmedRemark.substring(0, trimmedRemark.length() - source.length())
                        + normalized;
            }
        }
        return new ScheduleFlight(
                purposeId, craftId, mtow, flightNumber, registration,
                serviceDays, fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                normalizedRemark, normalizedSourceAircraftType, aircraftTablePresent);
    }

    public ScheduleFlight withPurposeId(String resolvedPurposeId) {
        String purpose = resolvedPurposeId == null ? "" : resolvedPurposeId.trim();
        return new ScheduleFlight(
                purpose.isBlank() ? purposeId : purpose, craftId, mtow, flightNumber,
                registration, serviceDays, fromAirport, toAirport, etd, eta, via,
                beginDate, endDate, remark, sourceAircraftType, aircraftTablePresent);
    }

    public ScheduleFlight withFlightNumber(String normalizedFlightNumber) {
        return new ScheduleFlight(
                purposeId, craftId, mtow, normalizedFlightNumber, registration,
                serviceDays, fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                remark, sourceAircraftType, aircraftTablePresent);
    }

    public ScheduleFlight withResolvedRoute(String resolvedFromAirport,
                                            String resolvedToAirport,
                                            String resolvedVia) {
        return new ScheduleFlight(
                purposeId, craftId, mtow, flightNumber, registration,
                serviceDays, resolvedFromAirport, resolvedToAirport, etd, eta, resolvedVia,
                beginDate, endDate, remark, sourceAircraftType, aircraftTablePresent);
    }

    public ScheduleFlight withRevisionDefaults(String inheritedPurposeId,
                                               long inheritedCraftId,
                                               BigDecimal inheritedMtow,
                                               String inheritedRegistration,
                                               String inheritedVia,
                                               String inheritedRemark,
                                               String inheritedSourceAircraftType,
                                               boolean inheritAircraft) {
        return new ScheduleFlight(
                purposeId == null || purposeId.isBlank() ? inheritedPurposeId : purposeId,
                inheritAircraft ? inheritedCraftId : craftId,
                inheritAircraft ? inheritedMtow : mtow, flightNumber,
                inheritAircraft && (registration == null || registration.isBlank())
                        ? inheritedRegistration : registration,
                serviceDays, fromAirport, toAirport, etd, eta,
                via == null || via.isBlank() ? inheritedVia : via,
                beginDate, endDate,
                remark == null || remark.isBlank() ? inheritedRemark : remark,
                inheritAircraft && (sourceAircraftType == null || sourceAircraftType.isBlank())
                        ? inheritedSourceAircraftType : sourceAircraftType,
                aircraftTablePresent);
    }

    public ScheduleFlight withInheritedAircraft(long inheritedCraftId,
                                                 BigDecimal inheritedMtow,
                                                 String inheritedRegistration,
                                                 String inheritedSourceAircraftType) {
        return new ScheduleFlight(
                purposeId, inheritedCraftId, inheritedMtow, flightNumber,
                registration == null || registration.isBlank()
                        ? inheritedRegistration : registration,
                serviceDays, fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                remark,
                sourceAircraftType == null || sourceAircraftType.isBlank()
                        ? inheritedSourceAircraftType : sourceAircraftType,
                aircraftTablePresent);
    }

    public ScheduleFlight asCancelledFrom(String originalPermitNumber) {
        String cancellation = "HUY TU " + (originalPermitNumber == null
                ? "PHEP GOC"
                : originalPermitNumber.trim());
        return new ScheduleFlight(
                purposeId, craftId, mtow, flightNumber, registration, serviceDays,
                fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                cancellation, sourceAircraftType, aircraftTablePresent);
    }

    public ScheduleFlight asCancelledReferenceFrom(String originalPermitNumber) {
        String cancellation = "HUY REF " + (originalPermitNumber == null
                ? "PHEP GOC"
                : originalPermitNumber.trim());
        return new ScheduleFlight(
                purposeId, craftId, mtow, flightNumber, registration, serviceDays,
                fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                cancellation, sourceAircraftType, aircraftTablePresent);
    }

    public ScheduleFlight withAircraftOnlyRemark() {
        String aircraft = sourceAircraftType == null ? "" : sourceAircraftType.trim();
        String revisedRemark = aircraft.isBlank()
                ? (remark == null ? null : remark.trim())
                : aircraft;
        return new ScheduleFlight(
                purposeId, craftId, mtow, flightNumber, registration, serviceDays,
                fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                revisedRemark, sourceAircraftType, aircraftTablePresent);
    }

    public ScheduleFlight withRemark(String revisedRemark) {
        return new ScheduleFlight(
                purposeId, craftId, mtow, flightNumber, registration, serviceDays,
                fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                revisedRemark, sourceAircraftType, aircraftTablePresent);
    }
}
