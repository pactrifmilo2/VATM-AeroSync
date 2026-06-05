package vatm.aerosync.worker.model;

import java.time.LocalDate;

public class FlightRow {

    private String callsign;
    private String from;
    private String to;
    private LocalDate dateFlight;

    public FlightRow() {
    }

    public FlightRow(String callsign, String from, String to, LocalDate dateFlight) {
        this.callsign = callsign;
        this.from = from;
        this.to = to;
        this.dateFlight = dateFlight;
    }

    public String getCallsign() {
        return callsign;
    }

    public void setCallsign(String callsign) {
        this.callsign = callsign;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public LocalDate getDateFlight() {
        return dateFlight;
    }

    public void setDateFlight(LocalDate dateFlight) {
        this.dateFlight = dateFlight;
    }
}
