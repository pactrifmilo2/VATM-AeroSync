package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.atfm.AtfmRevisionBaseline;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression coverage for the eight Word permits flagged as missing a format on 21/10/2026. */
class October21MissingFormatProfilesTest {

    private static final Map<String, ExpectedPermit> EXPECTED = expectedPermits();

    @Test
    void detectsAndParsesEveryWordMarkedAsMissingYamlFormat() throws Exception {
        Path directory = configuredDirectory();
        Assumptions.assumeTrue(Files.isDirectory(directory),
                "Set -Dpermit.october21.missing-format.dir to D:/vatm-storage/error/2026/10/21");

        WordPermitDocumentReader reader = new WordPermitDocumentReader();
        WordPermitFormatDetector detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        DocxSchedulePermitParser parser = new DocxSchedulePermitParser();
        int checked = 0;
        for (Map.Entry<String, ExpectedPermit> item : EXPECTED.entrySet()) {
            Path source = directory.resolve(item.getKey());
            assertThat(source).as("source permit %s", item.getKey()).isRegularFile();
            Path sidecar = directory.resolve(item.getKey() + ".log");
            assertThat(sidecar).as("sidecar for %s", item.getKey()).isRegularFile();
            assertThat(Files.readString(sidecar, StandardCharsets.UTF_8).trim())
                    .as("sidecar classification for %s", item.getKey())
                    .isEqualTo("File chưa có định dạng YAML phù hợp.");

            WordPermitDocument document = reader.read(source);
            DocxPermitFormatProfile profile = detector.detect(document, source.getFileName().toString());
            assertThat(profile.id()).as(item.getKey()).isEqualTo(item.getValue().profileId());

            var permit = parser.parse(document, source.getFileName().toString());
            assertThat(permit.normalizedPermitId()).as(item.getKey())
                    .isEqualTo(item.getValue().normalizedId());
            assertThat(permit.flights()).as(item.getKey())
                    .hasSize(item.getValue().flightCount());
            if (item.getValue().cancellationOnly()) {
                assertThat(permit.cancellationOnlyNotice()).isTrue();
                assertThat(permit.cancellationRevision()).isTrue();
                assertThat(permit.reviewOnly()).isTrue();
            }
            checked++;
        }
        assertThat(checked).isEqualTo(10);
    }

    @Test
    void cancellationNoticeUsesExistingAtfmRowsAndFailsClosedWithoutThem() throws Exception {
        Path directory = configuredDirectory();
        Assumptions.assumeTrue(Files.isDirectory(directory),
                "21/10/2026 permit corpus is not available");
        Path source = directory.resolve(
                "duongvtt_20261021_083135_email_000_CNL.LD-52.A.W.2026VN-FD-26OCT_8100fb14.docx");
        SchedulePermit parsed = new DocxSchedulePermitParser().parse(
                source, source.getFileName().toString());
        ScheduleFlight stored = new ScheduleFlight(
                "PAX", 1L, BigDecimal.ZERO, "AIQ301", null, "1234567",
                "VTBD", "VVTS", "0100", "0200", "R468",
                LocalDate.of(2026, 10, 25), LocalDate.of(2027, 3, 25), null, "A320");

        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findUsedRevisionNumbers(parsed)).thenReturn(Set.of());
        when(gateway.findRevisionBaseline(any(SchedulePermit.class))).thenReturn(Optional.of(
                new AtfmRevisionBaseline(1L, 2L, "AIQ", "LD 0052A/W/CHK/2026", List.of(stored))));
        ProcessingContext context = context(source, parsed);
        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit cancellation = context.getSchedulePermit();
        assertThat(cancellation.revisionCreatesNewPermit()).isTrue();
        assertThat(cancellation.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("AIQ301");
            assertThat(flight.remark()).isEqualTo("HUY TU LD 0052A/W/CHK/2026");
        });

        AtfmScheduleGateway missing = mock(AtfmScheduleGateway.class);
        when(missing.findUsedRevisionNumbers(parsed)).thenReturn(Set.of());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new RevisionReconciliationStep(missing).reconcile(context(source, parsed))))
                .hasMessageContaining("BR-CANCELLATION-BASE-NOT-FOUND");
    }

    @Test
    void mxdNextDayEtdPassesBusinessValidation() throws Exception {
        Path directory = configuredDirectory();
        Assumptions.assumeTrue(Files.isDirectory(directory),
                "21/10/2026 permit corpus is not available");
        Path source = directory.resolve(
                "thanhntm_20261021_094826_email_000_OF-110A-W26-MXD_b5682ef8.docx");
        SchedulePermit parsed = new DocxSchedulePermitParser().parse(
                source, source.getFileName().toString());
        assertThat(parsed.flights()).anySatisfy(flight -> assertThat(flight.etd()).isEqualTo("0005+"));
        SchedulePermit resolved = parsed.withFlights(parsed.flights().stream()
                .map(flight -> flight.withResolvedAircraft(1L, BigDecimal.ZERO))
                .toList());
        ProcessingContext context = context(source, resolved);
        new BusinessRuleValidatorStep().validate(context);
        assertThat(context.getRowValidationErrors()).isEmpty();
    }

    private static ProcessingContext context(Path source, SchedulePermit permit) {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, source.toString(), "october21", FileSourceType.FILESYSTEM, false));
        context.setFilePath(source);
        context.setSchedulePermit(permit);
        return context;
    }

    private static Path configuredDirectory() {
        String configured = System.getProperty("permit.october21.missing-format.dir", "");
        return configured.isBlank()
                ? Path.of("D:/vatm-storage/error/2026/10/21")
                : Path.of(configured);
    }

    private static Map<String, ExpectedPermit> expectedPermits() {
        Map<String, ExpectedPermit> values = new LinkedHashMap<>();
        values.put("duongvtt_20261021_083135_email_000_CNL.LD-52.A.W.2026VN-FD-26OCT_8100fb14.docx",
                new ExpectedPermit("aiq-caav-seasonal-landing-cancellation-notice",
                        "LD 0052A/W/CHK/2026", 0, true));
        values.put("giangpth_20261021_095142_email_001_OF-111_A_W_2026VN_CUH_07a3c1f6.doc",
                new ExpectedPermit("cuh-caav-seasonal-overflight-issued",
                        "O/F 0111A/W/CHK/2026", 2, false));
        values.put("giangpth_20261021_161446_email_001_OF-115_A_W_2026VN_CUA_70760fb1.doc",
                new ExpectedPermit("cua-caav-seasonal-overflight-issued",
                        "O/F 0115A/W/CHK/2026", 2, false));
        values.put("giangpth_20261021_161446_email_001_OF-116_A_W_2026VN_CBJ_bcd3f1e2.doc",
                new ExpectedPermit("cbj-english-overflight-issued",
                        "O/F 0116A/W/CHK/2026", 10, false));
        values.put("haanhlv_20261021_111345_email_002_LD-3858_CNK91_22OCT26_5e73c512.docx",
                new ExpectedPermit("cnk-caav-landing-issued", "LD 03858/CHK/2026", 2, false));
        values.put("lynk_20261021_085332_email_001_LD.56.A.W.2026VN.EK_1dc65034.doc",
                new ExpectedPermit("uae-caav-seasonal-landing-issued",
                        "LD 0056A/W/CHK/2026", 12, false));
        values.put("lynk_20261021_085333_email_004_LD.56.B.W.2026VN.EK_8bb2b579.doc",
                new ExpectedPermit("uae-caav-seasonal-landing-issued",
                        "LD 0056B/W/CHK/2026", 13, false));
        values.put("lynk_20261021_092754_email_000_LD.48.A.W.2026VN.EY_7dabec0e.doc",
                new ExpectedPermit("etd-caav-seasonal-landing-issued",
                        "LD 0048A/W/CHK/2026", 12, false));
        values.put("thanhntm_20261021_085847_email_000_OF-90B-W26-SIA_86b80593.docx",
                new ExpectedPermit("sia-caav-seasonal-overflight-issued",
                        "O/F 0090B/W/CHK/2026", 280, false));
        values.put("thanhntm_20261021_094826_email_000_OF-110A-W26-MXD_b5682ef8.docx",
                new ExpectedPermit("mxd-caav-seasonal-overflight-issued",
                        "O/F 0110A/W/CHK/2026", 88, false));
        return Map.copyOf(values);
    }

    private record ExpectedPermit(String profileId,
                                  String normalizedId,
                                  int flightCount,
                                  boolean cancellationOnly) {
    }
}
