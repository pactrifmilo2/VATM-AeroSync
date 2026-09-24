package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.exception.FormatValidationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WordPermitFormatDetectorTest {

    private final WordPermitFormatDetector detector =
            new WordPermitFormatDetector(new DocxPermitProfileCatalog());

    @Test
    void carrierDocumentWithoutMatchingYamlFailsInsteadOfUsingCaav() {
        WordPermitDocument document = document("""
                1. Carrier/Operator
                IATA code: C6 | ICAO code: MFX
                3. Schedules: UTC Time
                4. Purpose of Flight(s): Cargo
                Flight number | Effective from | Effective to
                MFX7632 | 11AUG26 | 11AUG26
                """);

        assertThatThrownBy(() -> detector.detect(document, "OF-MFX7632-11AUG26.docx"))
                .isInstanceOf(FormatValidationException.class)
                .hasMessageContaining("File chưa có định dạng YAML phù hợp");
    }

    @Test
    void carrierProfileWinsOverCaavWhenIssuedPermitIdentityExists() {
        WordPermitDocument document = document("""
                OF-5716/8/2026VN
                IATA code: C6 | ICAO code: MFX
                3. Schedules: UTC Time
                4. Purpose of Flight(s): Cargo
                Flight number | Effective from | Effective to
                MFX7632 | 11AUG26 | 11AUG26
                """);

        assertThat(detector.detect(document, "OF-5716-MFX7632.docx").id())
                .isEqualTo("mfx-english-overflight-issued");
    }

    @Test
    void detectsBambooPermitWhenMonthDigitsAreSplitByWordFormatting() {
        WordPermitDocument document = document("""
                Phép bay số: LD- 99999 /0 8 /2026VN
                Mã IATA: QH
                Mã ICAO: BAV
                """);

        assertThat(detector.detect(document, "LD-3063-QH18.docx").id())
                .isEqualTo("bav-vietnamese-landing-issued");
    }

    @Test
    void recognizesVietnameseCarrierIdentityWithoutFallingBackToGenericProfile() {
        WordPermitDocument document = document("""
                Phép bay số: LD- 99999 /0 8 /2026VN
                Mã IATA: QH
                Mã ICAO: BAV
                Số hiệu chuyến bay | Hiệu lực từ | Hiệu lực đến | Ngày trong tuần
                QH9999 | 01AUG26 | 01AUG26 | 6
                """);

        assertThat(detector.detect(document, "LD-99999-QH9999.docx").id())
                .isEqualTo("bav-vietnamese-landing-issued");
    }

    @Test
    void ICAOAloneSelectsCarrierProfile() {
        WordPermitDocument document = document("""
                OF-5716/8/2026VN
                ICAO code: MFX
                3. Schedules: UTC Time
                4. Purpose of Flight(s): Cargo
                Flight number | Effective from | Effective to
                MFX7632 | 11AUG26 | 11AUG26
                """);

        assertThat(detector.detect(document, "OF-MFX7632-11AUG26.docx").id())
                .isEqualTo("mfx-english-overflight-issued");
    }

    @Test
    void detectsCambodiaAirwaysScheduleChangeProfile() {
        WordPermitDocument document = document("""
                Ref: Permit approval Num: O/F-06/A/W/2023VN
                THE CAAV CONFIRMS SCHEDULE CHANGE AS FOLLOWING DETAILS
                IATA code: KR | ICAO code: KME
                2.2 Change to new Effective from 29Oct2023 to 30Mar2024
                FLT No. | From | To | Day(s) of OPS | STD | STA | Aircraft Type
                KME501 | 29Oct2023 | 30Mar2024 | 1234567 | VDPP | 0040 | VMMC | 0220 | A319/320
                """);

        assertThat(detector.detect(document, "OF-06A_W23_CHANGE_2.docx").id())
                .isEqualTo("kme-english-seasonal-overflight-revision");
    }

    @Test
    void missingIcaoDoesNotMatchUnrelatedHighPriorityCarriers() {
        var doc = document("OF-6455/9/2026VN\nIATA code: N/A\nICAO code:\nSchedules: UTC Time\nFlight number");
        var detected = detector.detect(doc, "private.docx");
        assertThat(detected.id()).doesNotStartWith("hke-").doesNotStartWith("mxd-");
    }

    @Test
    void billingCountryDoesNotIdentifyAir7() {
        var doc = document("OF-6187/8/2026VN\nName: PHILIPPINES AIRASIA\nIATA code: N/A\nICAO code: N/A\n"
                + "Schedules: UTC Time\nFlight number\nBilling address: SINGAPORE");
        assertThat(detector.detect(doc, "philippines.docx").id()).doesNotStartWith("prv-air7-");
    }

    private WordPermitDocument document(String rawContent) {
        return new WordPermitDocument(rawContent, rawContent, rawContent, List.of(), List.of());
    }
}
