package vatm.aerosync.worker.support;

import vatm.aerosync.common.enums.FileType;

import java.nio.file.Path;

public final class FileTypeDetector {

    private FileTypeDetector() {
    }

    public static FileType detect(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv")) {
            return FileType.CSV;
        }
        if (name.endsWith(".xlsx")) {
            return FileType.XLSX;
        }
        if (name.endsWith(".docx")) {
            return FileType.DOCX;
        }
        if (name.endsWith(".xml")) {
            return FileType.XML;
        }
        if (name.endsWith(".json")) {
            return FileType.JSON;
        }
        throw new IllegalArgumentException("Unsupported extension: " + name);
    }
}
