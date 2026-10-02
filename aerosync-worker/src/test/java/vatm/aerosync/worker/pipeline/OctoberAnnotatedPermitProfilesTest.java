package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OctoberAnnotatedPermitProfilesTest {
    private static final Pattern SELECTED_LINE = Pattern.compile(
            "(?iu)^\\d+\\.\\s+(?<path>[A-Z]:\\\\.*?\\.docx?)"
                    + "\\s+---\\s*cần xem và thêm format nếu chưa có.*$");

    @Test
    void parsesEveryWordFileSelectedInAnnotatedReport() throws Exception {
        String configured = System.getProperty("permit.october.annotated.report");
        Assumptions.assumeTrue(configured != null,
                "Set -Dpermit.october.annotated.report to check the annotated source documents");

        Map<String, Expected> expected = expectedFiles();
        Map<String, Path> selected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(configured), StandardCharsets.UTF_8)) {
            var matcher = SELECTED_LINE.matcher(line);
            if (matcher.matches()) {
                Path path = Path.of(matcher.group("path").trim());
                selected.put(path.getFileName().toString(), path);
            }
        }

        assertThat(selected).hasSize(29);
        assertThat(selected.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());

        var reader = new WordPermitDocumentReader();
        var detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        var parser = new DocxSchedulePermitParser();
        for (var entry : selected.entrySet()) {
            String fileName = entry.getKey();
            Path path = entry.getValue();
            Expected target = expected.get(fileName);

            assertThat(path).as(fileName).isRegularFile();
            WordPermitDocument document = reader.read(path);
            assertThat(detector.detect(document, fileName).id())
                    .as(fileName + " profile")
                    .isEqualTo(target.profile());

            SchedulePermit permit = parser.parse(document, fileName);
            assertThat(permit.operatorId()).as(fileName + " operator").isEqualTo(target.operator());
            assertThat(permit.normalizedPermitId()).as(fileName + " permit")
                    .isEqualTo(target.permit());
            assertThat(permit.flights()).as(fileName + " flights").hasSize(target.flights());
            assertThat(permit.originalFlights()).as(fileName + " original flights").isEmpty();
            assertThat(permit.flights().stream().map(flight -> flight.purposeId())
                    .collect(Collectors.toSet())).as(fileName + " purposes")
                    .isEqualTo(Set.of(target.purpose()));
            assertThat(permit.flights()).allSatisfy(flight -> {
                assertThat(flight.beginDate()).as(fileName + " begin date").isNotNull();
                assertThat(flight.endDate()).as(fileName + " end date").isNotNull();
                assertThat(flight.fromAirport()).as(fileName + " departure").isNotBlank();
                assertThat(flight.toAirport()).as(fileName + " arrival").isNotBlank();
                assertThat(flight.etd()).as(fileName + " ETD").matches("\\d{4}");
                assertThat(flight.sourceAircraftType()).as(fileName + " aircraft").isNotBlank();
            });
        }
    }

    private Map<String, Expected> expectedFiles() {
        Map<String, Expected> files = new LinkedHashMap<>();
        add(files, "duongvtt_20261001_082007_email_000_LD-09.A.W.2026VN-HY_1bd7294b.docx",
                "uzb-english-seasonal-landing-issued", "UZB", "LD 0009A/W/CHK/2026", 2, "PAX");
        add(files, "duongvtt_20261001_082008_email_000_LD-05.A.S.2026VN-T5_W26_SGN_269a74ad.docx",
                "tua-english-seasonal-landing-issued", "TUA", "LD 0005A/S/CHK/2026", 6, "PAX");
        add(files, "duongvtt_20261001_082008_email_000_LD-05.B.S.2026VN-T5_W26_HAN_f6405141.docx",
                "tua-english-seasonal-landing-issued", "TUA", "LD 0005B/S/CHK/2026", 4, "PAX");
        add(files, "duongvtt_20261001_082009_email_001_LD-07.A.W.2026VN-TG__HAN-SGN__4b77c7a9.docx",
                "tha-english-seasonal-landing-issued", "THA", "LD 0007A/W/CHK/2026", 10, "PAX");
        add(files, "duongvtt_20261001_095110_email_000_OF-64.A.W.2026VN-MMA_d7739608.docx",
                "mma-english-seasonal-overflight-issued", "MMA", "O/F 0064A/W/CHK/2026", 10, "PAX");
        add(files, "duongvtt_20261001_144808_email_001_LD-12.A.W.2026VN-VZ-_DAD.CXR.PQC__54be10be.docx",
                "tvj-english-seasonal-landing-issued", "TVJ", "LD 0012A/W/CHK/2026", 20, "PAX");
        add(files, "duongvtt_20261001_145423_email_000_LD-10.A.W.2026VN-BL_ea8e901a.docx",
                "pic-vietnamese-seasonal-landing-issued", "PIC", "LD 0010A/W/CHK/2026", 28, "PAX");
        add(files, "duongvtt_20261001_162547_email_000_LD-05.B.W.2026VN-T5_W26_HAN_b27b7061.docx",
                "tua-english-seasonal-landing-issued", "TUA", "LD 0005B/W/CHK/2026", 4, "PAX");
        add(files, "duongvtt_20261001_162649_email_000_LD-05.A.W.2026VN-T5_W26_SGN_90b5a7bb.docx",
                "tua-english-seasonal-landing-issued", "TUA", "LD 0005A/W/CHK/2026", 6, "PAX");
        add(files, "giangpth_20261001_143535_email_001_OF-58_A_W_2026VN_LKE_2127580c.doc",
                "lke-english-seasonal-overflight-issued", "LKE", "O/F 0058A/W/CHK/2026", 2, "PAX");
        add(files, "giangpth_20261001_143744_email_001_OF-60_A_W_2026VN_QDA_f0c6e900.doc",
                "qda-english-seasonal-overflight-issued", "QDA", "O/F 0060A/W/CHK/2026", 10, "PAX");
        add(files, "giangpth_20261001_143844_email_001_OF-59_A_W_2026VN_CYZ_e2a847c5.doc",
                "cyz-english-seasonal-overflight-issued", "CYZ", "O/F 0059A/W/CHK/2026", 2, "CAR");
        add(files, "giangpth_20261001_143945_email_001_OF-61_A_W_2026VN_CHB_fdb27eca.doc",
                "chb-english-seasonal-overflight-issued", "CHB", "O/F 0061A/W/CHK/2026", 6, "PAX");
        add(files, "giangpth_20261001_144048_email_001_OF-62_A_W_2026VN_CDC_cbcd134d.doc",
                "cdc-english-seasonal-overflight-issued", "CDC", "O/F 0062A/W/CHK/2026", 10, "PAX");
        add(files, "giangpth_20261001_144607_email_001_LD_02_A_W_2026VN_CZ_c0884c8a.doc",
                "csn-english-seasonal-landing-issued", "CSN", "LD 0002A/W/CHK/2026", 28, "PAX");
        add(files, "giangpth_20261001_144911_email_001_LD_03_A_W_2026VN_DR_b52b6bc9.doc",
                "rlh-english-seasonal-landing-issued", "RLH", "LD 0003A/W/CHK/2026", 6, "PAX");
        add(files, "haanhlv_20261001_095426_email_001_OF-7132-PVA7799_04OCT26_dfae54f5.docx",
                "pva-english-overflight-issued", "PVA", "O/F 07132/CHK/2026", 1, "PAX");
        add(files, "haanhlv_20261001_095842_email_001_OF-7135-N3262T_10OCT26_bc6e0f33.docx",
                "pva-english-overflight-issued", "PVA", "O/F 07135/CHK/2026", 1, "PAX");
        add(files, "haanhlv_20261001_153910_email_001_OF-65.A.W.2026VN.AMU_e6bb0fca.docx",
                "amu-english-seasonal-overflight-issued", "AMU", "O/F 0065A/W/CHK/2026", 6, "PAX");
        add(files, "haanhlv_20260930_175518_email_001_OF-7120-EGY01_9OCT26_96392d38.docx",
                "egy-english-overflight-issued", "EGY", "O/F 07120/CHK/2026", 1, "OTH");
        add(files, "lynk_20260824_162251_email_quarantine_000_OF-6029-KAC8247-06SEP-KAC8248-01OCT.docx",
                "kac-english-overflight-issued", "KAC", "O/F 06029/CHK/2026", 2, "FER");
        add(files, "lynk_20261001_164814_email_000_OF-52_B_W_2026_THY_PAX_9eadc662.doc",
                "thy-english-seasonal-overflight-issued", "THY", "O/F 0052B/W/CHK/2026", 16, "PAX");
        add(files, "thanhntm_20261001_170057_email_001_LD-06A-W26-MH_775500a5.docx",
                "mas-english-seasonal-landing-issued", "MAS", "LD 0006A/W/CHK/2026", 12, "PAX");
        add(files, "thanhntm_20261001_170159_email_001_LD-11A-W26-TH_3544ecd0.docx",
                "rmy-english-seasonal-landing-issued", "RMY", "LD 0011A/W/CHK/2026", 2, "CAR");
        add(files, "giangpth_20261002_155756_email_001_OF-67_A_W_2026VN_ABL_498d3658.doc",
                "abl-english-seasonal-overflight-issued", "ABL", "O/F 0067A/W/CHK/2026", 12, "PAX");
        add(files, "haanhlv_20261002_091353_email_002_OF-65.B.W.2026VN.AMU_28fbbc55.docx",
                "amu-english-seasonal-overflight-issued", "AMU", "O/F 0065B/W/CHK/2026", 2, "PAX");
        add(files, "haanhlv_20261002_112325_email_001_OF-7185-TVR4704_07OCT26_105731bf.docx",
                "tvr-caav-monthly-overflight-issued", "TVR", "O/F 07185/CHK/2026", 1, "CAR");
        add(files, "lynk_20261002_165650_email_000_OF-52_C_W_2026_THY_CGO_e15b8905.doc",
                "thy-english-seasonal-overflight-issued", "THY", "O/F 0052C/W/CHK/2026", 6, "CAR");
        add(files, "lynk_20261001_091335_email_000_OF-63A-W26-ACI-DHT_27770c6d.doc",
                "aci-english-seasonal-overflight-issued", "ACI", "O/F 0063A/W/CHK/2026", 6, "PAX");
        return files;
    }

    private void add(Map<String, Expected> files, String fileName, String profile,
                     String operator, String permit, int flights, String purpose) {
        files.put(fileName, new Expected(profile, operator, permit, flights, purpose));
    }

    private record Expected(String profile, String operator, String permit, int flights, String purpose) {
    }
}
