package vatm.aerosync.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.rabbit")
public class RabbitMqProperties {

    private String syncResultExchange = "sync.result";
    private String dashboardAlertsQueue = "dashboard.alerts.queue";
    private String fileIngestedExchange = "file.ingested";
    private String fileProcessingRoutingKey = "file.processing";
    private String permitReviewPublishExchange = "permit.review.publish";
    private String permitReviewPublishRoutingKey = "permit.review.publish";

    public String getSyncResultExchange() {
        return syncResultExchange;
    }

    public void setSyncResultExchange(String syncResultExchange) {
        this.syncResultExchange = syncResultExchange;
    }

    public String getDashboardAlertsQueue() {
        return dashboardAlertsQueue;
    }

    public void setDashboardAlertsQueue(String dashboardAlertsQueue) {
        this.dashboardAlertsQueue = dashboardAlertsQueue;
    }

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

    public String getPermitReviewPublishExchange() {
        return permitReviewPublishExchange;
    }

    public void setPermitReviewPublishExchange(String permitReviewPublishExchange) {
        this.permitReviewPublishExchange = permitReviewPublishExchange;
    }

    public String getPermitReviewPublishRoutingKey() {
        return permitReviewPublishRoutingKey;
    }

    public void setPermitReviewPublishRoutingKey(String permitReviewPublishRoutingKey) {
        this.permitReviewPublishRoutingKey = permitReviewPublishRoutingKey;
    }
}
