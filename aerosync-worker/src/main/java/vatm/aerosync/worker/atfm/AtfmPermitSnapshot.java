package vatm.aerosync.worker.atfm;

public record AtfmPermitSnapshot(long masterId, long permId, boolean matchesExpectedPermit) {
}
