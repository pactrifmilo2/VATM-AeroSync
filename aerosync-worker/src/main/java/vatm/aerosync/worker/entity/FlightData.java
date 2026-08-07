package vatm.aerosync.worker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

// @Entity intentionally excluded from the production EntityScan. This legacy
// training table is currently unused and has been removed from the active
// worker schema; keep the class only for historical test fixtures.
@Entity
@Table(name = "flight_data")
public class FlightData {

    @Id
    @SequenceGenerator(
            name = "flight_data_sequence_generator",
            sequenceName = "flight_data_seq",
            allocationSize = 50
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "flight_data_sequence_generator")
    private Long id;

    @Column(name = "sync_job_id", nullable = false)
    private Long syncJobId;

    @Column(nullable = false, length = 16)
    private String callsign;

    @Column(name = "from_airport", nullable = false, length = 3)
    private String fromAirport;

    @Column(name = "to_airport", nullable = false, length = 3)
    private String toAirport;

    @Column(name = "date_flight", nullable = false)
    private LocalDate dateFlight;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSyncJobId() {
        return syncJobId;
    }

    public void setSyncJobId(Long syncJobId) {
        this.syncJobId = syncJobId;
    }

    public String getCallsign() {
        return callsign;
    }

    public void setCallsign(String callsign) {
        this.callsign = callsign;
    }

    public String getFromAirport() {
        return fromAirport;
    }

    public void setFromAirport(String fromAirport) {
        this.fromAirport = fromAirport;
    }

    public String getToAirport() {
        return toAirport;
    }

    public void setToAirport(String toAirport) {
        this.toAirport = toAirport;
    }

    public LocalDate getDateFlight() {
        return dateFlight;
    }

    public void setDateFlight(LocalDate dateFlight) {
        this.dateFlight = dateFlight;
    }
}
