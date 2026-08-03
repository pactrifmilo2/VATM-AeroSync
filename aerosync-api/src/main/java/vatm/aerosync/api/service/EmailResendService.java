package vatm.aerosync.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.config.EmailResendProperties;
import vatm.aerosync.api.dto.EmailResendRequest;
import vatm.aerosync.api.dto.EmailResendResponse;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class EmailResendService {

    private final EmailResendProperties properties;
    private final EmailMetadataRepository emailMetadataRepository;
    private final FileRecordRepository fileRecordRepository;
    private final OutboundEmailSender emailSender;

    public EmailResendService(EmailResendProperties properties,
                              EmailMetadataRepository emailMetadataRepository,
                              FileRecordRepository fileRecordRepository,
                              OutboundEmailSender emailSender) {
        this.properties = properties;
        this.emailMetadataRepository = emailMetadataRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.emailSender = emailSender;
    }

    @Transactional(readOnly = true)
    public EmailResendResponse resend(EmailResendRequest request) {
        List<EmailMetadata> allMetadata = emailMetadataRepository
                .findByMessageIdOrderByAttachmentIndexAsc(request.messageId());
        if (allMetadata.isEmpty()) {
            throw new NoSuchElementException("Email message not found: " + request.messageId());
        }

        List<EmailMetadata> selected = allMetadata.stream()
                .filter(metadata -> request.statuses().contains(metadata.getProcessingStatus()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No attachments match the selected processing statuses");
        }

        Map<String, OutboundEmailSender.Attachment> attachments = new LinkedHashMap<>();
        int skipped = 0;
        for (EmailMetadata metadata : selected) {
            if (metadata.getSyncJob() == null) {
                skipped++;
                continue;
            }
            FileRecord latest = latestFileRecord(metadata.getSyncJob().getId());
            if (latest == null || latest.getStoredPath() == null) {
                skipped++;
                continue;
            }
            Path storedPath = Path.of(latest.getStoredPath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(storedPath)) {
                skipped++;
                continue;
            }
            String fileName = hasText(metadata.getAttachmentName())
                    ? metadata.getAttachmentName()
                    : latest.getOriginalFileName();
            attachments.putIfAbsent(storedPath.toString(),
                    new OutboundEmailSender.Attachment(storedPath, fileName));
        }
        if (attachments.isEmpty()) {
            throw new IllegalStateException("No archived attachment files are available to resend");
        }

        EmailMetadata original = allMetadata.getFirst();
        String recipient = hasText(properties.getRecipient())
                ? properties.getRecipient().trim()
                : null;
        Map<String, String> headers = Map.of(
                "X-AeroSync-Resend", "true",
                "X-AeroSync-Original-Message-ID", request.messageId(),
                "X-AeroSync-Original-Sender", nullToEmpty(original.getSender()),
                "X-AeroSync-Selected-Statuses", String.join(",", request.statuses().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList()));
        OutboundEmailSender.SendResult result = emailSender.send(new OutboundEmailSender.OutboundEmail(
                recipient,
                original.getSubject(),
                original.getBody(),
                new ArrayList<>(attachments.values()),
                headers));

        return new EmailResendResponse(
                request.messageId(),
                result.messageId(),
                result.recipient(),
                attachments.size(),
                skipped,
                true);
    }

    private FileRecord latestFileRecord(Long syncJobId) {
        Comparator<FileRecord> comparator = Comparator
                .comparing(FileRecord::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FileRecord::getId, Comparator.nullsFirst(Comparator.naturalOrder()));
        return fileRecordRepository.findBySyncJobId(syncJobId).stream()
                .max(comparator)
                .orElse(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
