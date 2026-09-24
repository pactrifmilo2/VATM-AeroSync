package vatm.aerosync.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import vatm.aerosync.api.config.ApiDataConfig;

@SpringBootApplication
@EnableScheduling
public class AerosyncApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[] {AerosyncApiApplication.class, ApiDataConfig.class}, args);
    }
}
