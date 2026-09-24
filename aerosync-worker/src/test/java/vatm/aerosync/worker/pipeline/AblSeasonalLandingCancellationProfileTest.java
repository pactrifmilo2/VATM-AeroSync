package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AblSeasonalLandingCancellationProfileTest {

    @TempDir
    Path tempDir;

    @Test
    void airBusanCancellationCreatesOnlyCancellationRowsInANewRevision() throws Exception {
        Path file = cancellationDocument();
        SchedulePermit parsed = new DocxSchedulePermitParser().parse(
                file, file.getFileName().toString());

        assertThat(parsed.sourcePermitNumber()).isEqualTo("LD-0035/A/S/2026VN");
        assertThat(parsed.normalizedPermitId()).isEqualTo("LD 0035A/S/CHK/2026");
        assertThat(parsed.permitNumber()).isEqualTo("0035A");
        assertThat(parsed.permitDate()).isEqualTo(LocalDate.of(2026, 7, 7));
        assertThat(parsed.operatorId()).isEqualTo("ABL");
        assertThat(parsed.flights()).hasSize(1);
        assertThat(parsed.originalFlights()).hasSize(1);
        assertThat(parsed.cancellationRevision()).isTrue();
        assertThat(parsed.implicitTableRevision()).isTrue();

        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit numbered = parsed.withRevisionNumber(3);
        when(gateway.findRevisionBaseline(numbered)).thenReturn(Optional.empty());
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, file.toString(), "a".repeat(64), FileSourceType.EMAIL, false));
        context.setFilePath(file);
        context.setSchedulePermit(parsed);

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit revision = context.getSchedulePermit();
        assertThat(revision.atfmTargetPermitId()).isEqualTo("LD 0035AR3/S/CHK/2026");
        assertThat(revision.flights()).hasSize(1);
        assertThat(revision.flights().getFirst().remark())
                .isEqualTo("HUY TU LD- 35A/S/2026VN");
    }

    private Path cancellationDocument() throws Exception {
        Path file = tempDir.resolve("R35A3_35A_LICH_HUY.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "CANCELLATION FORM FOR SCHEDULED FLIGHT TO/FROM VIET NAM - SUMMER 2026");
            paragraph(document, "1. Carrier/Operator:");
            paragraph(document, "- NAME: AIRBUSAN Co., Ltd.");
            paragraph(document, "- IATA 2 LETTER CODE: BX");
            paragraph(document, "- ICAO 3 LETTER CODE: ABL");
            paragraph(document, "2. Schedules: All times in UTC");
            table(document,
                    new String[] {"Flight number(1)", "Effective from(2)", "Effective to(3)",
                            "Days of services(4)", "Departure Airport(5)", "ETD(6)",
                            "Arrival Airport(7)", "ETA(8)", "Aircraft Type(9)"},
                    new String[] {"BX7315", "17Aug26", "13Sep26", "1------",
                            "PUS", "1305", "DAD", "1810", "32A/32S/32Q"});
            paragraph(document, "3. Type of services/ Purpose of Flight(s): Passenger");
            paragraph(document, "4. Reason of cancellation: operational reason");
            paragraph(document, "5. Ref to permit number: LD- 35A/S/2026VN");
            paragraph(document, "6. Applicant - Application is submitted on: 7 Jul 2026");
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void paragraph(XWPFDocument document, String text) {
        document.createParagraph().createRun().setText(text);
    }

    private void table(XWPFDocument document, String[] headers, String[] values) {
        XWPFTable table = document.createTable(2, headers.length);
        for (int index = 0; index < headers.length; index++) {
            table.getRow(0).getCell(index).setText(headers[index]);
            table.getRow(1).getCell(index).setText(values[index]);
        }
    }
}
