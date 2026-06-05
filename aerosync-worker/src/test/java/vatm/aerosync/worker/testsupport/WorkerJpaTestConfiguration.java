package vatm.aerosync.worker.testsupport;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = {
        "vatm.aerosync.common.entity",
        "vatm.aerosync.worker.entity"
})
@EnableJpaRepositories(basePackages = {
        "vatm.aerosync.common.repository",
        "vatm.aerosync.worker.repository"
})
public class WorkerJpaTestConfiguration {
}
