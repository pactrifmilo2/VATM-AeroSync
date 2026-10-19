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
                .startsWith("Lỗi xử lý chi tiết: Some new internal technical failure. Mã tham chiếu: ");
    }

    @Test
    void translatesFileReadAndParserErrorsWithActionableMessages() {
        assertThat(translator.translate("Cannot read file size"))
                .contains("quyền truy cập")
                .doesNotContain("Cannot read");
        assertThat(translator.translate(
                "Failed to parse Word permit: zip END header not found"))
                .contains("file bị hỏng")
                .contains("Chi tiết kỹ thuật: Failed to parse Word permit: zip END header not found")
                .contains("Mã tham chiếu:");
        assertThat(translator.translate("Failed to parse XLSX: invalid entry size"))
                .contains("Không thể đọc file Excel XLSX")
                .contains("Chi tiết kỹ thuật: Failed to parse XLSX: invalid entry size")
                .contains("Mã tham chiếu:");
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
    @Test
    void preservesDetailedAtfmUniqueConstraintMessageForOperators() {
        String message = "ATFM t\u1eeb ch\u1ed1i ghi do tr\u00f9ng kh\u00f3a duy nh\u1ea5t (ORA-00001). "
                + "Constraint Oracle: ATFM.UK_PERMDETAIL_SC_FLIGHT. "
                + "Chi ti\u1ebft Oracle: ORA-00001: unique constraint "
                + "(ATFM.UK_PERMDETAIL_SC_FLIGHT) violated";

        assertThat(translator.translate(message))
                .isEqualTo(message)
                .contains("ATFM.UK_PERMDETAIL_SC_FLIGHT")
                .contains("ORA-00001");
    }

    @Test
    void clarifiesLegacyOracleUniqueConstraintMessage() {
        assertThat(translator.translate(
                "Failed to insert scheduled permit in ATFM: ORA-00001: unique constraint "
                        + "(ATFM.UK_PERMMASTER_SC) violated"))
                .contains("tr\u00f9ng kh\u00f3a duy nh\u1ea5t")
                .contains("ORA-00001")
                .contains("ATFM.UK_PERMMASTER_SC");
    }

}
