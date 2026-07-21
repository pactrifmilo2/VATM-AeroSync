package vatm.aerosync.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "email_metadata",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_email_metadata_mailbox_attachment",
                columnNames = {"mailbox_folder", "uid_validity", "message_uid", "attachment_index"})
)
public class EmailMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_job_id")
    private SyncJob syncJob;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "mailbox_folder")
    private String mailboxFolder;

    @Column(name = "uid_validity")
    private Long uidValidity;

    @Column(name = "message_uid")
    private Long messageUid;

    @Column(name = "attachment_index")
    private Integer attachmentIndex;

    @Column(name = "attachment_name")
    private String attachmentName;

    @Column
    private String sender;

    @Column
    private String subject;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "attachment_count")
    private int attachmentCount;

    @Lob
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    @ColumnDefault("'DISCOVERED'")
    private EmailProcessingStatus processingStatus = EmailProcessingStatus.DISCOVERED;

    @Enumerated(EnumType.STRING)
    @Column(name = "acknowledgement_status", nullable = false)
    @ColumnDefault("'PENDING'")
    private EmailAcknowledgementStatus acknowledgementStatus = EmailAcknowledgementStatus.PENDING;

    @Column(name = "ingest_complete", nullable = false)
    @ColumnDefault("0")
    private boolean ingestComplete;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledgement_error", length = 2000)
    private String acknowledgementError;

    public Long getId() {
        return id;
    }

    public SyncJob getSyncJob() {
        return syncJob;
    }

    public void setSyncJob(SyncJob syncJob) {
        this.syncJob = syncJob;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getMailboxFolder() {
        return mailboxFolder;
    }

    public void setMailboxFolder(String mailboxFolder) {
        this.mailboxFolder = mailboxFolder;
    }

    public Long getUidValidity() {
        return uidValidity;
    }

    public void setUidValidity(Long uidValidity) {
        this.uidValidity = uidValidity;
    }

    public Long getMessageUid() {
        return messageUid;
    }

    public void setMessageUid(Long messageUid) {
        this.messageUid = messageUid;
    }

    public Integer getAttachmentIndex() {
        return attachmentIndex;
    }

    public void setAttachmentIndex(Integer attachmentIndex) {
        this.attachmentIndex = attachmentIndex;
    }

    public String getAttachmentName() {
        return attachmentName;
    }

    public void setAttachmentName(String attachmentName) {
        this.attachmentName = attachmentName;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public int getAttachmentCount() {
        return attachmentCount;
    }

    public void setAttachmentCount(int attachmentCount) {
        this.attachmentCount = attachmentCount;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public EmailProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(EmailProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public EmailAcknowledgementStatus getAcknowledgementStatus() {
        return acknowledgementStatus;
    }

    public void setAcknowledgementStatus(EmailAcknowledgementStatus acknowledgementStatus) {
        this.acknowledgementStatus = acknowledgementStatus;
    }

    public boolean isIngestComplete() {
        return ingestComplete;
    }

    public void setIngestComplete(boolean ingestComplete) {
        this.ingestComplete = ingestComplete;
    }

    public LocalDateTime getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public String getAcknowledgementError() {
        return acknowledgementError;
    }

    public void setAcknowledgementError(String acknowledgementError) {
        this.acknowledgementError = acknowledgementError;
    }
}
