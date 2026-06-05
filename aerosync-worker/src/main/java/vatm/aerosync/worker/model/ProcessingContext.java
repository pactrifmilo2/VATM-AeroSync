package vatm.aerosync.worker.model;

import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ProcessingContext {

    private final FileIngestedEvent event;
    private Path filePath;
    private String originalFileName;
    private FileType fileType;
    private final List<FlightRow> rows = new ArrayList<>();
    private long startedAtMillis = System.currentTimeMillis();

    public ProcessingContext(FileIngestedEvent event) {
        this.event = event;
    }

    public FileIngestedEvent getEvent() {
        return event;
    }

    public Path getFilePath() {
        return filePath;
    }

    public void setFilePath(Path filePath) {
        this.filePath = filePath;
        this.originalFileName = filePath.getFileName().toString();
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public FileType getFileType() {
        return fileType;
    }

    public void setFileType(FileType fileType) {
        this.fileType = fileType;
    }

    public List<FlightRow> getRows() {
        return rows;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public void setStartedAtMillis(long startedAtMillis) {
        this.startedAtMillis = startedAtMillis;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAtMillis;
    }
}
