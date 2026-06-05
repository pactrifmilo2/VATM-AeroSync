package vatm.aerosync.ingest.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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
    DirectExchange fileIngestedExchange(RabbitMqProperties properties) {
        return new DirectExchange(properties.getFileIngestedExchange(), true, false);
    }

    @Bean
    Queue fileProcessingQueue(RabbitMqProperties properties) {
        return QueueBuilder.durable(properties.getFileProcessingQueue())
                .maxPriority(10)
                .build();
    }

    @Bean
    Binding fileProcessingBinding(Queue fileProcessingQueue,
                                  DirectExchange fileIngestedExchange,
                                  RabbitMqProperties properties) {
        return BindingBuilder.bind(fileProcessingQueue)
                .to(fileIngestedExchange)
                .with(properties.getFileProcessingRoutingKey());
    }
}
