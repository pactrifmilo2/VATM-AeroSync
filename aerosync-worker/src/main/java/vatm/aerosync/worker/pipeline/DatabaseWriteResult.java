package vatm.aerosync.worker.pipeline;

import vatm.aerosync.common.enums.SyncStatus;

public record DatabaseWriteResult(SyncStatus status, int rowsSaved, String message) {
    public static DatabaseWriteResult success(int rowsSaved) {
        return new DatabaseWriteResult(SyncStatus.SUCCESS, rowsSaved, "Database save completed");
    }
}
