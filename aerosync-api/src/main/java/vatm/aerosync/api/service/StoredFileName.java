package vatm.aerosync.api.service;

import vatm.aerosync.common.entity.FileRecord;

final class StoredFileName {

    private StoredFileName() {
    }

    static String from(FileRecord record) {
        if (record == null) {
            return null;
        }
        String path = record.getStoredPath();
        if (path == null || path.isBlank()) {
            return record.getOriginalFileName();
        }
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String fileName = separator >= 0 ? path.substring(separator + 1) : path;
        return fileName.isBlank() ? record.getOriginalFileName() : fileName;
    }
}
