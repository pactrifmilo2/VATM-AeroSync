package vatm.aerosync.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackages = {
        "vatm.aerosync.common.entity",
        "vatm.aerosync.api.entity"
})
@EnableJpaRepositories(basePackages = {
        "vatm.aerosync.common.repository",
        "vatm.aerosync.api.repository"
})
@EnableConfigurationProperties({ApiProperties.class, RabbitMqProperties.class})
public class ApiDataConfig {
}
