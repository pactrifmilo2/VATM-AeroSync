package vatm.aerosync.worker.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
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

    @Bean
    DirectExchange fileProcessingFailureExchange(RabbitMqProperties properties) {
        return new DirectExchange(properties.getFileProcessingFailureExchange(), true, false);
    }

    @Bean
    Queue fileProcessingFailureQueue(RabbitMqProperties properties) {
        return QueueBuilder.durable(properties.getFileProcessingFailureQueue()).build();
    }

    @Bean
    Binding fileProcessingFailureBinding(Queue fileProcessingFailureQueue,
                                         DirectExchange fileProcessingFailureExchange,
                                         RabbitMqProperties properties) {
        return BindingBuilder.bind(fileProcessingFailureQueue)
                .to(fileProcessingFailureExchange)
                .with(properties.getFileProcessingFailureRoutingKey());
    }

    @Bean
    StatelessRetryOperationsInterceptor fileProcessingRetryInterceptor(
            RabbitTemplate rabbitTemplate,
            RabbitMqProperties properties) {
        RepublishMessageRecoverer recoverer = new RepublishMessageRecoverer(
                rabbitTemplate,
                properties.getFileProcessingFailureExchange(),
                properties.getFileProcessingFailureRoutingKey());
        return RetryInterceptorBuilder.stateless()
                .maxRetries(properties.getMaxRetries())
                .backOffOptions(
                        properties.getRetryInitialIntervalMs(),
                        properties.getRetryMultiplier(),
                        properties.getRetryMaxIntervalMs())
                .recoverer(recoverer)
                .build();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            StatelessRetryOperationsInterceptor fileProcessingRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setContainerCustomizer(container -> {
            container.setAdviceChain(fileProcessingRetryInterceptor);
            container.setDefaultRequeueRejected(false);
        });
        return factory;
    }

    @Bean
    FanoutExchange syncResultExchange(RabbitMqProperties properties) {
        return new FanoutExchange(properties.getSyncResultExchange(), true, false);
    }
}
