package vatm.aerosync.api.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vatm.aerosync.api.config.LegacyUserSecurityProperties;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyTUserAccountRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private LegacyTUserAccountRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-user-security;MODE=Oracle;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS T_USERMENU");
        jdbcTemplate.execute("DROP TABLE IF EXISTS T_USERS");
        jdbcTemplate.execute("""
                CREATE TABLE T_USERS (
                    USERID NUMBER PRIMARY KEY,
                    USERNAME VARCHAR2(20),
                    USERPASS VARCHAR2(50),
                    USERACTIVE NUMBER
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE T_USERMENU (
                    ID NUMBER PRIMARY KEY,
                    USER_ID NUMBER,
                    MENU_ID NUMBER,
                    R_EDIT NUMBER,
                    R_PUB NUMBER
                )
                """);
        LegacyUserSecurityProperties properties =
                new LegacyUserSecurityProperties();
        repository = new LegacyTUserAccountRepository(
                jdbcTemplate,
                properties);
    }

    @Test
    void readsLegacyAccountAndAggregatesPermitPermissions() {
        jdbcTemplate.update(
                "INSERT INTO T_USERS VALUES (?, ?, ?, ?)",
                10,
                "admin",
                "legacy-hash",
                1);
        jdbcTemplate.update(
                "INSERT INTO T_USERMENU VALUES (?, ?, ?, ?, ?)",
                1,
                10,
                403,
                1,
                1);

        LegacyTUserAccount account =
                repository.findByUsernameIgnoreCase("ADMIN").orElseThrow();

        assertThat(account.username()).isEqualTo("admin");
        assertThat(account.active()).isTrue();
        assertThat(account.canEditPermits()).isTrue();
        assertThat(account.canPublishPermits()).isTrue();
    }
}
