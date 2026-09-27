package vatm.aerosync.api.service;

import java.util.Locale;
import java.util.Set;

public enum ReportFileType {
    WORD(Set.of("doc", "docx", "rtf")),
    EXCEL(Set.of("xls", "xlsx", "xlsm", "csv")),
    PDF(Set.of("pdf")),
    IMAGE(Set.of("png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp")),
    NONE(Set.of());

    private final Set<String> extensions;
    ReportFileType(Set<String> extensions) { this.extensions = extensions; }

    public boolean matches(String storedFileName, String attachmentName) {
        String name = hasText(storedFileName) ? storedFileName : attachmentName;
        if (this == NONE) return !hasText(name);
        if (!hasText(name)) return false;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot == name.length() - 1) return false;
        return extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
