package vatm.aerosync.api.service;

import vatm.aerosync.common.enums.EmailProcessingStatus;
import java.time.LocalDateTime;

public record IncomingReportFilter(String query, EmailProcessingStatus processingStatus,
                                   LocalDateTime from, LocalDateTime to,
                                   ReportFileType fileType) { }
