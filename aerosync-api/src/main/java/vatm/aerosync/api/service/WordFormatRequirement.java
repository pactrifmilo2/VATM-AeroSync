package vatm.aerosync.api.service;

import java.util.Locale;

final class WordFormatRequirement {

    private WordFormatRequirement() {
    }

    static boolean isRequired(String fileName, String errorMessage) {
        if (!isWord(fileName) || errorMessage == null || errorMessage.isBlank()) {
            return false;
        }
        String lower = errorMessage.toLowerCase(Locale.ROOT);
        return lower.contains("cần thêm format yaml")
                || lower.contains("file word chưa có format yaml phù hợp")
                || lower.contains("không hỗ trợ định dạng file word này vì chưa có format yaml phù hợp")
                || lower.contains("không hỗ trợ định dạng giấy phép bay word này; không có format yaml nào phù hợp")
                || lower.contains("unsupported word permit format")
                || lower.contains("no format profile matched");
    }

    private static boolean isWord(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".docm")
                || lower.endsWith(".dot") || lower.endsWith(".dotx");
    }
}
