package vatm.aerosync.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.ingest.config.EmailProperties;
import vatm.aerosync.ingest.config.IngestProperties;
import vatm.aerosync.ingest.config.RabbitMqProperties;

@SpringBootApplication
@EnableScheduling
@EntityScan("vatm.aerosync.common.entity")
@EnableJpaRepositories("vatm.aerosync.common.repository")
@EnableConfigurationProperties({
        FilePathProperties.class,
        IngestProperties.class,
        EmailProperties.class,
        RabbitMqProperties.class
})
public class AerosyncIngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(AerosyncIngestApplication.class, args);
    }
}
