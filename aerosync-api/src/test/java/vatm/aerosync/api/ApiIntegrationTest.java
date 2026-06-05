package vatm.aerosync.api;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = {AerosyncApiApplication.class, vatm.aerosync.api.config.ApiDataConfig.class})
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management-alpine");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void contextLoadsWithRabbitMq() {
        assertThat(rabbitTemplate).isNotNull();
        assertThat(rabbit.isRunning()).isTrue();
    }
}
