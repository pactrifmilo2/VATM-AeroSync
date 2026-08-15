package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VietnameseErrorMessageTranslatorTest {

    private final VietnameseErrorMessageTranslator translator = new VietnameseErrorMessageTranslator();

    @Test
    void translatesEmailAndFileSystemTechnicalErrors() {
        assertThat(translator.translate("Could not move message"))
                .isEqualTo("Không thể chuyển email sang thư mục đích trên máy chủ thư.");
        assertThat(translator.translate("java.nio.file.AccessDeniedException: permit.docx"))
                .isEqualTo("Không có quyền truy cập file hoặc thư mục cần xử lý.");
        assertThat(translator.translate("The process cannot access the file because it is being used by another process"))
                .isEqualTo("File đang được ứng dụng khác sử dụng nên chưa thể đọc.");
    }

    @Test
    void translatesCommonPermitErrorsAndPreservesDynamicValues() {
        assertThat(translator.translate("Unsupported Word permit format; no format profile matched"))
                .isEqualTo("CẦN THÊM FORMAT YAML: File Word chưa có format YAML phù hợp.");
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
                .startsWith("Không thể hoàn tất yêu cầu vì nguyên nhân chưa được phân loại. Mã tham chiếu: ");
    }

    @Test
    void translatesFileReadAndParserErrorsWithActionableMessages() {
        assertThat(translator.translate("Cannot read file size"))
                .contains("quyền truy cập")
                .doesNotContain("Cannot read");
        assertThat(translator.translate(
                "Failed to parse Word permit: zip END header not found"))
                .contains("file bị hỏng")
                .doesNotContain("Failed to parse");
        assertThat(translator.translate("Failed to parse XLSX: invalid entry size"))
                .contains("Không thể đọc file Excel XLSX")
                .doesNotContain("Failed to parse");
        assertThat(translator.translate("Invalid ETD: giờ 25"))
                .isEqualTo("Giờ khởi hành dự kiến (ETD) không hợp lệ: giờ 25.");
    }

    @Test
    void translatesBusinessRuleMessagesAndKeepsRuleCode() {
        assertThat(translator.translate(
                "BR-CALLSIGN: Row 1: Invalid callsign (2 total validation errors)"))
                .isEqualTo("BR-CALLSIGN: Dòng 1: Số hiệu chuyến bay không hợp lệ. (tổng cộng 2 lỗi kiểm tra)");
        assertThat(translator.translate("Aircraft mapping is required"))
                .isEqualTo("Không ánh xạ được loại tàu bay với dữ liệu ATFM.");
    }
}
