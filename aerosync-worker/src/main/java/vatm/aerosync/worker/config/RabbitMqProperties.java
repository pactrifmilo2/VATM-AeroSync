package vatm.aerosync.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.rabbit")
public class RabbitMqProperties {

    private String fileIngestedExchange = "file.ingested";
    private String fileProcessingRoutingKey = "file.processing";
    private String fileProcessingQueue = "file.processing.queue";
    private String fileProcessingFailureExchange = "file.processing.failed";
    private String fileProcessingFailureRoutingKey = "file.processing.failed";
    private String fileProcessingFailureQueue = "file.processing.failed.queue";
    private int maxRetries = 2;
    private long retryInitialIntervalMs = 5000;
    private double retryMultiplier = 2.0;
    private long retryMaxIntervalMs = 30000;
    private String syncResultExchange = "sync.result";

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

    public String getFileProcessingFailureExchange() {
        return fileProcessingFailureExchange;
    }

    public void setFileProcessingFailureExchange(String fileProcessingFailureExchange) {
        this.fileProcessingFailureExchange = fileProcessingFailureExchange;
    }

    public String getFileProcessingFailureRoutingKey() {
        return fileProcessingFailureRoutingKey;
    }

    public void setFileProcessingFailureRoutingKey(String fileProcessingFailureRoutingKey) {
        this.fileProcessingFailureRoutingKey = fileProcessingFailureRoutingKey;
    }

    public String getFileProcessingFailureQueue() {
        return fileProcessingFailureQueue;
    }

    public void setFileProcessingFailureQueue(String fileProcessingFailureQueue) {
        this.fileProcessingFailureQueue = fileProcessingFailureQueue;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryInitialIntervalMs() {
        return retryInitialIntervalMs;
    }

    public void setRetryInitialIntervalMs(long retryInitialIntervalMs) {
        this.retryInitialIntervalMs = retryInitialIntervalMs;
    }

    public double getRetryMultiplier() {
        return retryMultiplier;
    }

    public void setRetryMultiplier(double retryMultiplier) {
        this.retryMultiplier = retryMultiplier;
    }

    public long getRetryMaxIntervalMs() {
        return retryMaxIntervalMs;
    }

    public void setRetryMaxIntervalMs(long retryMaxIntervalMs) {
        this.retryMaxIntervalMs = retryMaxIntervalMs;
    }

    public String getSyncResultExchange() {
        return syncResultExchange;
    }

    public void setSyncResultExchange(String syncResultExchange) {
        this.syncResultExchange = syncResultExchange;
    }
}
