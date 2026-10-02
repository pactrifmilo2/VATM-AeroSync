package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HvnReferencedSeasonNewPermitProfileTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parseAndReconcile_createsNewScPermitUsingReferencedSeason() throws Exception {
        Path file = createDocument("LD-2993/8/2026VN");

        SchedulePermit parsed = normalize(file,
                parser.parse(file, file.getFileName().toString()));

        assertThat(parsed.referencedSeasonNewPermit()).isTrue();
        assertThat(parsed.sourcePermitNumber()).isEqualTo("LD-2993/8/2026VN");
        assertThat(parsed.normalizedPermitId()).isEqualTo("LD 02993/CHK/2026");
        assertThat(parsed.permitNumber()).isEqualTo("2993");
        assertThat(parsed.version()).isEqualTo("A");
        assertThat(parsed.season()).isNull();
        assertThat(parsed.flightType()).isEqualTo("NO");
        assertThat(parsed.referencedPermitId()).isEqualTo("LD 0068A/S/CHK/2026");
        assertThat(parsed.reference()).isEqualTo("LD-68/A/S/2026VN");
        assertThat(parsed.originalFlights()).hasSize(1);
        assertThat(parsed.flights()).extracting(flight -> flight.flightNumber())
                .containsExactly("HVN1573", "HVN1466", "HVN7591");
        assertThat(parsed.implicitTableRevision()).isFalse();

        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findReferencedSeason(parsed)).thenReturn(Optional.of("S"));
        ProcessingContext context = context(file, parsed);

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit result = context.getSchedulePermit();
        assertThat(result.normalizedPermitId()).isEqualTo("LD 02993/S/CHK/2026");
        assertThat(result.atfmTargetPermitId()).isEqualTo("LD 02993/S/CHK/2026");
        assertThat(result.permitNumber()).isEqualTo("2993");
        assertThat(result.version()).isEqualTo("A");
        assertThat(result.season()).isEqualTo("S");
        assertThat(result.flightType()).isEqualTo("NO");
        assertThat(result.usesScheduledStorage()).isTrue();
        assertThat(result.importRevision()).isFalse();
        assertThat(result.flights()).hasSize(4);
        assertThat(result.flights().getFirst().remark()).isEqualTo("HUY REF LD-68/A/S/2026VN");
        assertThat(result.flights().subList(1, 4))
                .extracting(flight -> flight.remark())
                .containsExactly("321/320", "321/320", "321/320");
    }

    @Test
    void parse_preservesNumberSuffixOnlyWhenPresentInSourcePermit() throws Exception {
        Path file = createDocument("LD-2994A/8/2026VN");

        SchedulePermit result = normalize(file,
                parser.parse(file, file.getFileName().toString()));

        assertThat(result.sourcePermitNumber()).isEqualTo("LD-2994A/8/2026VN");
        assertThat(result.normalizedPermitId()).isEqualTo("LD 02994A/CHK/2026");
        assertThat(result.permitNumber()).isEqualTo("2994A");
        assertThat(result.version()).isEqualTo("A");
    }

    @Test
    void parse_doesNotTreatSeasonMarkerAsNumberSuffix() throws Exception {
        Path file = createDocument("LD-2995S/8/2026VN");

        SchedulePermit parsed = normalize(file,
                parser.parse(file, file.getFileName().toString()));
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findReferencedSeason(parsed)).thenReturn(Optional.of("S"));
        ProcessingContext context = context(file, parsed);

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit result = context.getSchedulePermit();
        assertThat(result.sourcePermitNumber()).isEqualTo("LD-2995/8/2026VN");
        assertThat(result.normalizedPermitId()).isEqualTo("LD 02995/S/CHK/2026");
        assertThat(result.permitNumber()).isEqualTo("2995");
        assertThat(result.version()).isEqualTo("A");
        assertThat(result.season()).isEqualTo("S");
    }

    @Test
    void parseAndReconcile_mapsProvidedHvnDocument() {
        String samplePath = System.getProperty("permit.hvn.referenced-season.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.hvn.referenced-season.sample.path to validate the supplied HVN document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured HVN document does not exist");

        SchedulePermit parsed = normalize(file,
                parser.parse(file, file.getFileName().toString()));
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findReferencedSeason(parsed)).thenReturn(Optional.of("S"));
        ProcessingContext context = context(file, parsed);

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit result = context.getSchedulePermit();
        assertThat(result.normalizedPermitId()).isEqualTo("LD 02993/S/CHK/2026");
        assertThat(result.permitNumber()).isEqualTo("2993");
        assertThat(result.version()).isEqualTo("A");
        assertThat(result.season()).isEqualTo("S");
        assertThat(result.flightType()).isEqualTo("NO");
        assertThat(result.reference()).isEqualTo("LD-68/A/S/2026VN");
        assertThat(result.originalFlights()).hasSize(21);
        assertThat(result.flights()).hasSize(48);
        assertThat(result.flights().subList(0, 21))
                .allSatisfy(flight -> assertThat(flight.remark())
                        .isEqualTo("HUY REF LD-68/A/S/2026VN"));
        assertThat(result.flights().subList(21, 48))
                .extracting(flight -> flight.flightNumber())
                .contains("HVN1573", "HVN1466", "HVN7591");
    }

    @Test
    void parseProvidedHvnNumberDocument_doesNotInventNumberSuffix() {
        String samplePath = System.getProperty("permit.hvn.number.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.hvn.number.sample.path to validate the supplied HVN number document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured HVN document does not exist");

        SchedulePermit result = normalize(file,
                parser.parse(file, file.getFileName().toString()));

        assertThat(result.sourcePermitNumber()).isEqualTo("LD-02994/8/2026VN");
        assertThat(result.normalizedPermitId()).isEqualTo("LD 02994/CHK/2026");
        assertThat(result.permitNumber()).isEqualTo("2994");
        assertThat(result.version()).isEqualTo("A");
    }

    @Test
    void parseProvidedHvnSeasonMarkerDocument_doesNotAppendSeasonToNumber() {
        String samplePath = System.getProperty("permit.hvn.season-marker.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.hvn.season-marker.sample.path to validate the supplied HVN document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured HVN document does not exist");

        SchedulePermit parsed = normalize(file,
                parser.parse(file, file.getFileName().toString()));
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findReferencedSeason(parsed)).thenReturn(Optional.of("S"));
        ProcessingContext context = context(file, parsed);

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit result = context.getSchedulePermit();
        assertThat(result.sourcePermitNumber()).isEqualTo("LD-2995/8/2026VN");
        assertThat(result.normalizedPermitId()).isEqualTo("LD 02995/S/CHK/2026");
        assertThat(result.permitNumber()).isEqualTo("2995");
        assertThat(result.version()).isEqualTo("A");
        assertThat(result.season()).isEqualTo("S");
    }

    private ProcessingContext context(Path file, SchedulePermit permit) {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, file.toString(), "a".repeat(64), FileSourceType.EMAIL, false));
        context.setFilePath(file);
        context.setSchedulePermit(permit);
        return context;
    }

    private SchedulePermit normalize(Path file, SchedulePermit permit) {
        ProcessingContext context = context(file, permit);
        new NormalizerStep(java.time.ZoneId.of("UTC")).normalize(context);
        return context.getSchedulePermit();
    }

    private Path createDocument(String newPermitNumber) throws Exception {
        Path file = tempDir.resolve(newPermitNumber.replace('/', '-') + "-HVN-change.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HÀ NỘI, NGÀY 17/8/2026");
            paragraph(document, newPermitNumber);
            paragraph(document, "Lịch bay mùa IATA: Hè 2026");
            paragraph(document, "Tên: VIETNAM AIRLINES");
            paragraph(document, "Mã IATA (nếu có): VN");
            paragraph(document, "Mã ICAO (nếu có): HVN");

            paragraph(document, "2.1. Lịch bay gốc (Giờ UTC)");
            schedule(document, "VN1573", "23:55", "01:50+1", "LD-68/A/S/2026VN", false);
            paragraph(document, "2.2. Lịch bay mới (Giờ UTC)");
            schedule(document, "VN1573", "23:15", "01:10+1", null, true);
            paragraph(document, "2.3. CB TĂNG CHUYẾN (Giờ UTC)");
            schedule(document, "VN1466", "07:55", "09:20", null, false);
            paragraph(document, "2.5. CB CHỞ KHÁCH SANG CHUYẾN SÂN (Giờ UTC)");
            schedule(document, "VN7591", "22:05", "23:10", "LD-2939/8/2026VN", false);
            paragraph(document, "Đường hàng không: THEO PHÉP BAY LD-68/A/S/2026VN");
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void schedule(XWPFDocument document,
                          String flightNumber,
                          String etd,
                          String eta,
                          String originalPermit,
                          boolean includeRemark) {
        java.util.List<String> headers = new java.util.ArrayList<>(java.util.List.of(
                "Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến", "Ngày trong tuần",
                "Sân bay cất cánh", "Giờ dự kiến cất cánh", "Sân bay hạ cánh",
                "Giờ dự kiến hạ cánh", "Loại tàu bay"));
        java.util.List<String> values = new java.util.ArrayList<>(java.util.List.of(
                flightNumber, "18AUG26", "18AUG26", "2", "HAN", etd, "DLI", eta, "321/320"));
        if (originalPermit != null) {
            headers.add("Số phép bay đã cấp");
            values.add(originalPermit);
        } else if (includeRemark) {
            headers.add("Lưu ý");
            values.add("Bay sớm");
        }
        XWPFTable table = document.createTable(2, headers.size());
        for (int column = 0; column < headers.size(); column++) {
            table.getRow(0).getCell(column).setText(headers.get(column));
            table.getRow(1).getCell(column).setText(values.get(column));
        }
    }

    private void paragraph(XWPFDocument document, String value) {
        document.createParagraph().createRun().setText(value);
    }
}
