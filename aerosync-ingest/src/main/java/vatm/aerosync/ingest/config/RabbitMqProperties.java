package vatm.aerosync.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.rabbit")
public class RabbitMqProperties {

    private String fileIngestedExchange = "file.ingested";
    private String fileProcessingRoutingKey = "file.processing";
    private String fileProcessingQueue = "file.processing.queue";
    private String syncResultExchange = "sync.result";
    private String emailAcknowledgementQueue = "email.acknowledgement.queue";

    public String getFileIngestedExchange() {
        return fileIngestedExchange;
    }

    public void setFileIngestedExchange(String fileIngestedExchange) {
        this.fileIngestedExchange = fileIngestedExchange;
    }

    public String getFileProcessingRoutingKey() {
        return fileProcessingRoutingKey;
    }

    public void setFileProcessingRoutingKey(String fileProcessingRoutingKey) {
        this.fileProcessingRoutingKey = fileProcessingRoutingKey;
    }

    public String getFileProcessingQueue() {
        return fileProcessingQueue;
    }

    public void setFileProcessingQueue(String fileProcessingQueue) {
        this.fileProcessingQueue = fileProcessingQueue;
    }

    public String getSyncResultExchange() {
        return syncResultExchange;
    }

    public void setSyncResultExchange(String syncResultExchange) {
        this.syncResultExchange = syncResultExchange;
    }

    public String getEmailAcknowledgementQueue() {
        return emailAcknowledgementQueue;
    }

    public void setEmailAcknowledgementQueue(String emailAcknowledgementQueue) {
        this.emailAcknowledgementQueue = emailAcknowledgementQueue;
    }
}
