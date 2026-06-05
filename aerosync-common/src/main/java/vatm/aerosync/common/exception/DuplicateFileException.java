package vatm.aerosync.common.exception;

public class DuplicateFileException extends RuntimeException {

    private final String fileHash;

    public DuplicateFileException(String fileHash) {
        super("Duplicate file detected with SHA-256 hash: %s".formatted(fileHash));
        this.fileHash = fileHash;
    }

    public String getFileHash() {
        return fileHash;
    }
}
