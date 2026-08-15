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
                .isEqualTo("Bộ phân tích không thể đọc cấu trúc nội dung của file.");
        assertThat(VietnameseStoredErrorMessage.translate("Sync job not found: 99"))
                .isEqualTo("Không tìm thấy tác vụ xử lý tương ứng trong cơ sở dữ liệu.");
        assertThat(VietnameseStoredErrorMessage.translate(
                "Đã xảy ra lỗi kỹ thuật trong quá trình xử lý file. Vui lòng kiểm tra nhật ký hệ thống."))
                .isEqualTo("Bản ghi lỗi cũ không lưu được nguyên nhân cụ thể. Hãy gửi lại file để hệ thống kiểm tra và ghi nhận lỗi đầy đủ.");
    }
}
