package vatm.aerosync.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqConfigTest {

    @Test
    void providesJacksonJsonMessageConverter() {
        RabbitMqConfig config = new RabbitMqConfig();
        assertThat(config.jackson2JsonMessageConverter()).isInstanceOf(Jackson2JsonMessageConverter.class);
    }
}
