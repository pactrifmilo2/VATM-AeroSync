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
    private String permitTrainingValidationExchange =
            "permit.training.validation";
    private String permitTrainingValidationRoutingKey =
            "permit.training.validation";
    private String permitProfileValidationExchange =
            "permit.profile.validation";
    private String permitProfileValidationRoutingKey =
            "permit.profile.validation";
    private String permitProfileCanaryExchange = "permit.profile.canary";
    private String permitProfileCanaryRoutingKey = "permit.profile.canary";

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

    public String getPermitTrainingValidationExchange() {
        return permitTrainingValidationExchange;
    }

    public void setPermitTrainingValidationExchange(
            String permitTrainingValidationExchange) {
        this.permitTrainingValidationExchange =
                permitTrainingValidationExchange;
    }

    public String getPermitTrainingValidationRoutingKey() {
        return permitTrainingValidationRoutingKey;
    }

    public void setPermitTrainingValidationRoutingKey(
            String permitTrainingValidationRoutingKey) {
        this.permitTrainingValidationRoutingKey =
                permitTrainingValidationRoutingKey;
    }

    public String getPermitProfileValidationExchange() {
        return permitProfileValidationExchange;
    }

    public void setPermitProfileValidationExchange(
            String permitProfileValidationExchange) {
        this.permitProfileValidationExchange = permitProfileValidationExchange;
    }

    public String getPermitProfileValidationRoutingKey() {
        return permitProfileValidationRoutingKey;
    }

    public void setPermitProfileValidationRoutingKey(
            String permitProfileValidationRoutingKey) {
        this.permitProfileValidationRoutingKey =
                permitProfileValidationRoutingKey;
    }

    public String getPermitProfileCanaryExchange() {
        return permitProfileCanaryExchange;
    }

    public void setPermitProfileCanaryExchange(
            String permitProfileCanaryExchange) {
        this.permitProfileCanaryExchange = permitProfileCanaryExchange;
    }

    public String getPermitProfileCanaryRoutingKey() {
        return permitProfileCanaryRoutingKey;
    }

    public void setPermitProfileCanaryRoutingKey(
            String permitProfileCanaryRoutingKey) {
        this.permitProfileCanaryRoutingKey = permitProfileCanaryRoutingKey;
    }
}
