package vatm.aerosync.worker.service;

import org.springframework.stereotype.Component;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

@Component
public class PermitSemanticHasher {

    public String hash(SchedulePermit permit) {
        StringBuilder canonical = new StringBuilder()
                .append(permit.normalizedPermitId()).append('|')
                .append(permit.permitDate()).append('|')
                .append(permit.operatorId()).append('|')
                .append(permit.validHours()).append('|')
                .append(permit.flightType());
        permit.flights().stream()
                .sorted(Comparator.comparing(ScheduleFlight::flightNumber)
                        .thenComparing(ScheduleFlight::beginDate)
                        .thenComparing(ScheduleFlight::etd))
                .forEach(flight -> canonical.append('\n')
                        .append(flight.purposeId()).append('|')
                        .append(flight.craftId()).append('|')
                        .append(flight.mtow()).append('|')
                        .append(flight.flightNumber()).append('|')
                        .append(flight.serviceDays()).append('|')
                        .append(flight.fromAirport()).append('|')
                        .append(flight.toAirport()).append('|')
                        .append(flight.etd()).append('|')
                        .append(flight.eta()).append('|')
                        .append(flight.via()).append('|')
                        .append(flight.beginDate()).append('|')
                        .append(flight.endDate()).append('|')
                        .append(flight.remark()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
