package vatm.aerosync.worker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VietnameseStoredErrorMessageTest {

    @Test
    void translatesMessagesBeforeTheyAreStoredInErrorMessage() {
        assertThat(VietnameseStoredErrorMessage.translate(
                "Unsupported Word permit format; no format profile matched"))
                .isEqualTo("Không hỗ trợ định dạng file Word này vì chưa có format YAML phù hợp.");
        assertThat(VietnameseStoredErrorMessage.translate(
                "The process cannot access the file because it is being used by another process"))
                .isEqualTo("File đang được ứng dụng khác sử dụng nên chưa thể đọc.");
        assertThat(VietnameseStoredErrorMessage.translate("Unexpected parser exception"))
                .isEqualTo("Đã xảy ra lỗi kỹ thuật trong quá trình xử lý file. Vui lòng kiểm tra nhật ký hệ thống.");
    }
}
