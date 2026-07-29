package vatm.aerosync.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqConfigTest {

    @Test
    void providesJacksonJsonMessageConverter() {
        RabbitMqConfig config = new RabbitMqConfig();
        assertThat(config.jackson2JsonMessageConverter()).isInstanceOf(Jackson2JsonMessageConverter.class);
    }

    @Test
    void declaresDedicatedFailureTopology() {
        RabbitMqConfig config = new RabbitMqConfig();
        RabbitMqProperties properties = new RabbitMqProperties();

        DirectExchange exchange = config.fileProcessingFailureExchange(properties);
        Queue queue = config.fileProcessingFailureQueue(properties);
        Binding binding = config.fileProcessingFailureBinding(queue, exchange, properties);

        assertThat(exchange.getName()).isEqualTo("file.processing.failed");
        assertThat(queue.getName()).isEqualTo("file.processing.failed.queue");
        assertThat(binding.getRoutingKey()).isEqualTo("file.processing.failed");
    }
}
