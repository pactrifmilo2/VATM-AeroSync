package vatm.aerosync.worker.service;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.PermitReviewFlightSnapshot;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

@Component
public class PermitReviewSnapshotMapper {

    public PermitReviewSnapshot toSnapshot(SchedulePermit permit) {
        return new PermitReviewSnapshot(
                permit.sourcePermitNumber(),
                permit.normalizedPermitId(),
                permit.permitNumber(),
                permit.authorId(),
                permit.permitType(),
                permit.version(),
                permit.season(),
                permit.permitDate(),
                permit.operatorId(),
                permit.reference(),
                permit.validHours(),
                permit.billingAddress(),
                permit.flightType(),
                permit.iataAirportsAllowed(),
                permit.emptyAirwaysAllowed(),
                permit.rawContent(),
                permit.flights().stream().map(this::toSnapshot).toList());
    }

    public SchedulePermit toPermit(PermitReviewSnapshot snapshot) {
        return new SchedulePermit(
                snapshot.sourcePermitNumber(),
                snapshot.normalizedPermitId(),
                snapshot.permitNumber(),
                snapshot.authorId(),
                snapshot.permitType(),
                snapshot.version(),
                snapshot.season(),
                snapshot.permitDate(),
                snapshot.operatorId(),
                snapshot.reference(),
                snapshot.validHours(),
                snapshot.billingAddress(),
                snapshot.flightType(),
                snapshot.iataAirportsAllowed(),
                snapshot.emptyAirwaysAllowed(),
                false,
                snapshot.rawContent(),
                snapshot.flights().stream().map(this::toFlight).toList());
    }

    private PermitReviewFlightSnapshot toSnapshot(ScheduleFlight flight) {
        return new PermitReviewFlightSnapshot(
                flight.purposeId(),
                flight.craftId(),
                flight.mtow(),
                flight.flightNumber(),
                flight.registration(),
                flight.serviceDays(),
                flight.fromAirport(),
                flight.toAirport(),
                flight.etd(),
                flight.eta(),
                flight.via(),
                flight.beginDate(),
                flight.endDate(),
                flight.remark(),
                flight.sourceAircraftType());
    }

    private ScheduleFlight toFlight(PermitReviewFlightSnapshot flight) {
        return new ScheduleFlight(
                flight.purposeId(),
                flight.craftId(),
                flight.mtow(),
                flight.flightNumber(),
                flight.registration(),
                flight.serviceDays(),
                flight.fromAirport(),
                flight.toAirport(),
                flight.etd(),
                flight.eta(),
                flight.via(),
                flight.beginDate(),
                flight.endDate(),
                flight.remark(),
                flight.sourceAircraftType());
    }
}
