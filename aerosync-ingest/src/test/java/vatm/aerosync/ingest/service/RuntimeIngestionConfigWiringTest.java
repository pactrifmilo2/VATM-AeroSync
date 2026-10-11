package vatm.aerosync.ingest.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vatm.aerosync.ingest.config.IngestProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeIngestionConfigWiringTest {
    @Test
    void constructorIsAutowiredWhenSpringScansTheService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(IngestProperties.class, IngestProperties::new);
            context.register(RuntimeIngestionConfig.class);
            context.refresh();
            assertThat(context.getBean(RuntimeIngestionConfig.class)).isNotNull();
        }
    }
}
