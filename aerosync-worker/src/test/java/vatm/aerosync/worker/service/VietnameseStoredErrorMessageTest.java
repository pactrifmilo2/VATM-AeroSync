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
    void preservesWordFormatLocationAndExplainsHowToAddMissingProfile() {
        String mismatch = "WORD_FORMAT_MISMATCH: Format YAML: vfc-test. V\u1ecb tr\u00ed: B\u1ea3ng 2, d\u00f2ng ti\u00eau \u0111\u1ec1 1. "
                + "Gi\u00e1 tr\u1ecb hi\u1ec7n c\u00f3: C2='Start date'. Kh\u00e1c bi\u1ec7t: thi\u1ebfu effectiveFrom. "
                + "Gi\u00e1 tr\u1ecb/alias mong \u0111\u1ee3i: effectiveFrom = [Eff from]. C\u00e1ch ch\u1ec9nh: \u0111\u1ed5i t\u00ean c\u1ed9t.";
        assertThat(VietnameseStoredErrorMessage.translate(mismatch))
                .contains("Format YAML: vfc-test", "B\u1ea3ng 2", "Start date", "Eff from", "C\u00e1ch ch\u1ec9nh");
        assertThat(VietnameseStoredErrorMessage.translate(
                "WORD_FORMAT_MISSING: No YAML format profile matched"))
                .contains("ch\u01b0a c\u00f3 format YAML/m\u1eabu ph\u00f9 h\u1ee3p",
                        "g\u1eedi file Word m\u1eabu", "qu\u1ea3n tr\u1ecb vi\u00ean");
    }

    @Test
    void replacesLegacyManualReviewMessageWithNonBlockingExplanation() {
        String translated = VietnameseStoredErrorMessage.translate(
                "Giấy phép bay cần được kiểm tra thủ công trước khi ghi vào ATFM.");

        assertThat(translated)
                .isEqualTo("Permit đã được phân tích; cờ reviewOnly không còn chặn ghi vào ATFM.")
                .doesNotContain("Giấy phép bay cần được kiểm tra thủ công trước khi ghi vào ATFM.");
    }

    @Test
    void truncatesLongVietnameseMessagesToOracleByteLimitWithoutReplacementCharacters() {
        String input = "l\u1ed7i ".repeat(700);

        String stored = VietnameseStoredErrorMessage.fitErrorMessageColumn(input);

        assertThat(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(2000);
        assertThat(stored).endsWith("\u2026");
        assertThat(stored).doesNotContain("\uFFFD");
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

    @Test
    void translatesUnreadableAndMissingBaselineAircraftWithRowAndA000Fallback() {
        assertThat(VietnameseStoredErrorMessage.translate(
                "Schedule row 3: Could not read aircraft type from permit file"))
                .contains("Dòng lịch bay 3", "Không đọc được", "A000");
        assertThat(VietnameseStoredErrorMessage.translate(
                "Schedule row 4: Aircraft type is missing from revision and could not be resolved from original permit"))
                .contains("Dòng lịch bay 4", "phép gốc", "A000");
        assertThat(VietnameseStoredErrorMessage.translate(
                "Schedule row 5: Aircraft code B799 maps to different ATFM aircraft; matching craft IDs: [1, 2]"))
                .contains("Dòng lịch bay 5", "nhiều loại", "A000");
    }
    @Test
    void explainsAirportFallbackWithSourceAndPlaceholderCode() {
        assertThat(VietnameseStoredErrorMessage.translate(
                "Schedule row 3: Airport code not found in ATFM: XXX; fallback VVVV applied"))
                .contains("Dòng lịch bay 3", "XXX", "VVVV");
    }

    @Test
    void explainsOracleUniqueConstraintViolationsWithConstraintAndOriginalDetail() {
        String translated = VietnameseStoredErrorMessage.translate(
                "Failed to insert permit O/F 05199/S/CHK/2026 in scheduled tables "
                        + "T_PERMMASTER_SC/T_PERMDETAIL_SC in ATFM: ORA-00001: unique constraint "
                        + "(ATFM.UK_PERMDETAIL_SC_FLIGHT) violated. "
                        + "Constraint metadata: owner=ATFM, table=T_PERMDETAIL_SC, columns=FLIGHTNBR, BEGINDATE");

        assertThat(translated)
                .contains("vi ph\u1ea1m kh\u00f3a duy nh\u1ea5t")
                .contains("ATFM.UK_PERMDETAIL_SC_FLIGHT")
                .contains("ORA-00001")
                .contains("unique constraint")
                .contains("Oracle kh\u00f4ng cung c\u1ea5p tr\u1ef1c ti\u1ebfp")
                .contains("T_PERMDETAIL_SC")
                .contains("FLIGHTNBR, BEGINDATE");

        assertThat(VietnameseStoredErrorMessage.translate(
                "ORA-00001: unique constraint (ATFM.UK_AIRPORT_PERMIT) violated"))
                .contains("vi ph\u1ea1m kh\u00f3a duy nh\u1ea5t")
                .contains("ATFM.UK_AIRPORT_PERMIT");
    }

    @Test
    void keepsDetailedOracleMessageForOtherDatabaseErrors() {
        String translated = VietnameseStoredErrorMessage.translate(
                "Failed to update permit in ATFM: ORA-01400: cannot insert NULL into "
                        + "(ATFM.T_PERMMASTER_SC.OPER_ID)");

        assertThat(translated)
                .contains("M\u00e3 l\u1ed7i Oracle: ORA-01400")
                .contains("Chi ti\u1ebft Oracle")
                .contains("OPER_ID");
    }

}
