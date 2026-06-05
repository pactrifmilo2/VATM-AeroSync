package vatm.aerosync.worker;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = AerosyncWorkerApplication.class)
@ActiveProfiles("test")
class WorkerIntegrationTest {

    @Container
    @ServiceConnection
    static org.testcontainers.containers.RabbitMQContainer rabbit =
            new org.testcontainers.containers.RabbitMQContainer("rabbitmq:3-management-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void contextLoadsWithRabbitMqAndRedis() {
        assertThat(rabbitTemplate).isNotNull();
        assertThat(rabbit.isRunning()).isTrue();
        assertThat(redis.isRunning()).isTrue();
    }
}
