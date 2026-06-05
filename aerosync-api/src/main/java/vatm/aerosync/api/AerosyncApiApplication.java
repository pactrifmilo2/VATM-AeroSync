package vatm.aerosync.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import vatm.aerosync.api.config.ApiDataConfig;

@SpringBootApplication
public class AerosyncApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[] {AerosyncApiApplication.class, ApiDataConfig.class}, args);
    }
}
