package vatm.aerosync.common.dto;

import vatm.aerosync.common.enums.FileSourceType;

public class FileIngestedEvent {

    private Long syncJobId;
    private String tempFilePath;
    private String fileHash;
    private FileSourceType sourceType;
    private boolean priority;

    public FileIngestedEvent() {
    }

    public FileIngestedEvent(Long syncJobId, String tempFilePath, String fileHash,
                             FileSourceType sourceType, boolean priority) {
        this.syncJobId = syncJobId;
        this.tempFilePath = tempFilePath;
        this.fileHash = fileHash;
        this.sourceType = sourceType;
        this.priority = priority;
    }

    public Long getSyncJobId() {
        return syncJobId;
    }

    public void setSyncJobId(Long syncJobId) {
        this.syncJobId = syncJobId;
    }

    public String getTempFilePath() {
        return tempFilePath;
    }

    public void setTempFilePath(String tempFilePath) {
        this.tempFilePath = tempFilePath;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public FileSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(FileSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public boolean isPriority() {
        return priority;
    }

    public void setPriority(boolean priority) {
        this.priority = priority;
    }
}
