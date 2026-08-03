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
        String sourceAircraftType
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
                fromAirport, toAirport, etd, eta, via, beginDate, endDate, remark, null);
    }

    public ScheduleFlight withResolvedAircraft(long resolvedCraftId, BigDecimal resolvedMtow) {
        return new ScheduleFlight(
                purposeId, resolvedCraftId, resolvedMtow, flightNumber, registration,
                serviceDays, fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                remark, sourceAircraftType);
    }

    public ScheduleFlight withFlightNumber(String normalizedFlightNumber) {
        return new ScheduleFlight(
                purposeId, craftId, mtow, normalizedFlightNumber, registration,
                serviceDays, fromAirport, toAirport, etd, eta, via, beginDate, endDate,
                remark, sourceAircraftType);
    }

    public ScheduleFlight withResolvedRoute(String resolvedFromAirport,
                                            String resolvedToAirport,
                                            String resolvedVia) {
        return new ScheduleFlight(
                purposeId, craftId, mtow, flightNumber, registration,
                serviceDays, resolvedFromAirport, resolvedToAirport, etd, eta, resolvedVia,
                beginDate, endDate, remark, sourceAircraftType);
    }

    public ScheduleFlight withRevisionDefaults(long inheritedCraftId,
                                               BigDecimal inheritedMtow,
                                               String inheritedRegistration,
                                               String inheritedVia,
                                               String inheritedRemark) {
        return new ScheduleFlight(
                purposeId, inheritedCraftId, inheritedMtow, flightNumber,
                registration == null || registration.isBlank() ? inheritedRegistration : registration,
                serviceDays, fromAirport, toAirport, etd, eta,
                via == null || via.isBlank() ? inheritedVia : via,
                beginDate, endDate,
                remark == null || remark.isBlank() ? inheritedRemark : remark,
                sourceAircraftType);
    }
}
