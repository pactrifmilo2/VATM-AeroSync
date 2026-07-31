package vatm.aerosync.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import vatm.aerosync.common.config.FilePathProperties;

@Configuration
@EntityScan(basePackages = {
        "vatm.aerosync.common.entity",
        "vatm.aerosync.api.entity"
})
@EnableJpaRepositories(basePackages = {
        "vatm.aerosync.common.repository",
        "vatm.aerosync.api.repository"
})
@EnableConfigurationProperties({
        ApiProperties.class,
        RabbitMqProperties.class,
        LegacyUserSecurityProperties.class,
        PermitTrainingProperties.class,
        FilePathProperties.class
})
public class ApiDataConfig {
}
