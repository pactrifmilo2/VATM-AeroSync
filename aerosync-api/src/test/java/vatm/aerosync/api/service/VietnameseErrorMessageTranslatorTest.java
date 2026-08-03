package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VietnameseErrorMessageTranslatorTest {

    private final VietnameseErrorMessageTranslator translator = new VietnameseErrorMessageTranslator();

    @Test
    void translatesCommonPermitErrorsAndPreservesDynamicValues() {
        assertThat(translator.translate("Unsupported Word permit format; no format profile matched"))
                .isEqualTo("Không hỗ trợ định dạng giấy phép bay Word này; không có format YAML nào phù hợp.");
        assertThat(translator.translate("Permit number not found for profile vfc-vietnamese-landing-change"))
                .isEqualTo("Không tìm thấy số phép bay theo format vfc-vietnamese-landing-change.");
        assertThat(translator.translate(
                "PERMIT-REVISION-REVIEW: Permit LD 02822/S/CHK/2026 was previously imported with different schedule data"))
                .isEqualTo("PERMIT-REVISION-REVIEW: Phép bay LD 02822/S/CHK/2026 đã được nhập trước đó nhưng có dữ liệu lịch bay khác.");
        assertThat(translator.translate(
                "BR-ATFM-REFERENCE: ATFM lookup not found: M_OPER.OPER_ICAO=POS"))
                .isEqualTo("BR-ATFM-REFERENCE: Không tìm thấy dữ liệu đối chiếu trong ATFM: M_OPER.OPER_ICAO=POS.");
    }

    @Test
    void replacesUnknownEnglishTechnicalErrorsWithVietnameseFallback() {
        assertThat(translator.translate("Some new internal technical failure"))
                .isEqualTo("Đã xảy ra lỗi trong quá trình xử lý. Vui lòng xem nhật ký kỹ thuật để biết chi tiết.");
    }
}
