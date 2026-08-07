package vatm.aerosync.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.worker.config.RabbitMqProperties;

@SpringBootApplication
@ComponentScan(
        basePackages = "vatm.aerosync.worker",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "vatm\\.aerosync\\.worker\\.testsupport\\..*"
        )
)
@EnableScheduling
@EntityScan(basePackages = {
        "vatm.aerosync.common.entity"
})
@EnableJpaRepositories(basePackages = {
        "vatm.aerosync.common.repository"
})
@EnableConfigurationProperties({FilePathProperties.class, RabbitMqProperties.class})
public class AerosyncWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AerosyncWorkerApplication.class, args);
    }
}
