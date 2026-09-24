package vatm.aerosync.worker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VietnameseStoredErrorMessageTest {

    @Test
    void translatesMessagesBeforeTheyAreStoredInErrorMessage() {
        assertThat(VietnameseStoredErrorMessage.translate(
                "Unsupported Word permit format; no format profile matched"))
                .isEqualTo("CẦN THÊM FORMAT YAML: File Word chưa có format YAML phù hợp.");
        assertThat(VietnameseStoredErrorMessage.translate(
                "The process cannot access the file because it is being used by another process"))
                .isEqualTo("File đang được ứng dụng khác sử dụng nên chưa thể đọc.");
        assertThat(VietnameseStoredErrorMessage.translate("Invalid effective-to date: 24OCT"))
                .isEqualTo("Ngày kết thúc hiệu lực không hợp lệ: 24OCT.");
        assertThat(VietnameseStoredErrorMessage.translate("Unexpected parser exception"))
                .contains("Unexpected parser exception")
                .startsWith("Không thể phân tích cấu trúc nội dung file")
                .contains("Mã tham chiếu:");
        assertThat(VietnameseStoredErrorMessage.translate("Sync job not found: 99"))
                .isEqualTo("Không tìm thấy tác vụ xử lý tương ứng trong cơ sở dữ liệu.");
        assertThat(VietnameseStoredErrorMessage.translate(
                "Đã xảy ra lỗi kỹ thuật trong quá trình xử lý file. Vui lòng kiểm tra nhật ký hệ thống."))
                .contains("nguyên nhân cụ thể", "Chi tiết kỹ thuật:", "Vui lòng kiểm tra nhật ký hệ thống")
                .contains("Mã tham chiếu:");
    }

    @Test
    void keepsRowAndLookupValuesForAircraftAndRouteErrors() {
        assertThat(VietnameseStoredErrorMessage.translate(
                "Schedule row 2: Aircraft type not found in ATFM dictionary: B799 (tried: B799) (1 aircraft resolution errors)"))
                .contains("Dòng lịch bay 2", "B799", "đã thử");
        assertThat(VietnameseStoredErrorMessage.translate(
                "BR-ROUTE-NOT-FOUND: ATFM route not found: VVNB -> VHHH"))
                .contains("VVNB -> VHHH");
        assertThat(VietnameseStoredErrorMessage.translate(
                "BR-ROUTE-DOCUMENT-UNREADABLE: Cannot read route from permit file"))
                .isEqualTo("Kh\u00f4ng \u0111\u1ecdc \u0111\u01b0\u1ee3c \u0111\u01b0\u1eddng bay trong file");
    }
}
