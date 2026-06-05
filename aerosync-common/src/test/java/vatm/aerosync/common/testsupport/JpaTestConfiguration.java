package vatm.aerosync.common.testsupport;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("vatm.aerosync.common.entity")
@EnableJpaRepositories("vatm.aerosync.common.repository")
public class JpaTestConfiguration {
}
