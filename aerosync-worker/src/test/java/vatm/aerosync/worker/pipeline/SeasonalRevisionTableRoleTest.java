package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SeasonalRevisionTableRoleTest {
    @Test
    void expectedDepartureHeaderDoesNotTurnNewTableIntoOld() {
        var header = List.of("Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến", "Ngày trong tuần",
                "Sân bay đi", "Giờ dự kiến đi", "Sân bay đến", "Giờ dự kiến đến");
        var old = List.of(header,
                List.of("VU672", "01OCT26", "11OCT26", "1--45-7", "SGN", "0200", "DAD", "0325"),
                List.of("VU672", "15OCT26", "23OCT26", "1--45-7", "SGN", "0200", "DAD", "0325"));
        var revised = List.of(header,
                List.of("VU672", "01OCT26", "11OCT26", "1--45-7", "SGN", "0150", "DAD", "0315"),
                List.of("VU672", "15OCT26", "23OCT26", "1--45-7", "SGN", "0150", "DAD", "0315"));
        String text = "Phép bay sửa đổi\nHà Nội, 23/09/2026\nPhép bay số: LD-79/A/S/2026VN/REV7\n"
                + "Mã IATA: VU\nMã ICAO: VAG\nTên: VIETRAVEL AIRLINES\nCác chi tiết khác không thay đổi";
        var document = new WordPermitDocument(text, "", text, List.of(old, revised),
                List.of("2.1. Lịch bay gốc", "2.2. Lịch bay mới"));
        assertRows(new DocxSchedulePermitParser().parse(document, "LD-79-REV7.docx"));
    }

    @Test
    void realLd79Rev7HasTwoOldAndTwoNewRows() {
        String sample = System.getProperty("permit.ld79.sample");
        Assumptions.assumeTrue(sample != null, "Set permit.ld79.sample for real local Word");
        assertRows(new DocxSchedulePermitParser().parse(Path.of(sample), "LD-79-REV7.docx"));
    }

    private void assertRows(SchedulePermit permit) {
        assertThat(permit.originalFlights()).extracting(ScheduleFlight::etd).containsExactly("0200", "0200");
        assertThat(permit.flights()).extracting(ScheduleFlight::etd).containsExactly("0150", "0150");
        assertThat(permit.revisionNumber()).isEqualTo(7);
    }
}
