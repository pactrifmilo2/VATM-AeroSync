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
        String remark
) {
}
