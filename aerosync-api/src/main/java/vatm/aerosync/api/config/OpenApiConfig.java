package vatm.aerosync.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aerosyncOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("VATM AeroSync API")
                        .description("Administrative monitoring and reporting API for VATM AeroSync.")
                        .version("1.0.0")
                        .contact(new Contact().name("VATM AeroSync")));
    }
}
