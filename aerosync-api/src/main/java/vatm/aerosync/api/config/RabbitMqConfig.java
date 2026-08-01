package vatm.aerosync.api.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RabbitMqProperties.class)
public class RabbitMqConfig {

    @Bean
    Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    FanoutExchange syncResultExchange(RabbitMqProperties properties) {
        return new FanoutExchange(properties.getSyncResultExchange(), true, false);
    }

    @Bean
    Queue dashboardAlertsQueue(RabbitMqProperties properties) {
        return new Queue(properties.getDashboardAlertsQueue(), true);
    }

    @Bean
    Binding dashboardAlertsBinding(Queue dashboardAlertsQueue, FanoutExchange syncResultExchange) {
        return BindingBuilder.bind(dashboardAlertsQueue).to(syncResultExchange);
    }

    @Bean
    DirectExchange permitReviewPublishExchange(RabbitMqProperties properties) {
        return new DirectExchange(properties.getPermitReviewPublishExchange(), true, false);
    }

    @Bean
    DirectExchange permitTrainingValidationExchange(
            RabbitMqProperties properties) {
        return new DirectExchange(
                properties.getPermitTrainingValidationExchange(),
                true,
                false);
    }

    @Bean
    DirectExchange permitProfileValidationExchange(
            RabbitMqProperties properties) {
        return new DirectExchange(
                properties.getPermitProfileValidationExchange(),
                true,
                false);
    }

    @Bean
    DirectExchange permitProfileCanaryExchange(
            RabbitMqProperties properties) {
        return new DirectExchange(
                properties.getPermitProfileCanaryExchange(),
                true,
                false);
    }
}
