package vatm.aerosync.api.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vatm.aerosync.api.config.LegacyUserSecurityProperties;

import java.util.Optional;

@Repository
public class LegacyTUserAccountRepository {

    private static final String FIND_ACCOUNT_SQL = """
            SELECT u.USERID,
                   u.USERNAME,
                   u.USERPASS,
                   NVL(u.USERACTIVE, 0) AS USERACTIVE,
                   MAX(CASE
                         WHEN m.MENU_ID = ? THEN NVL(m.R_EDIT, 0)
                         ELSE 0
                       END) AS CAN_EDIT,
                   MAX(CASE
                         WHEN m.MENU_ID = ? THEN NVL(m.R_PUB, 0)
                         ELSE 0
                       END) AS CAN_PUBLISH
              FROM T_USERS u
              LEFT JOIN T_USERMENU m ON m.USER_ID = u.USERID
             WHERE UPPER(u.USERNAME) = UPPER(?)
             GROUP BY u.USERID,
                      u.USERNAME,
                      u.USERPASS,
                      u.USERACTIVE
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LegacyUserSecurityProperties properties;

    public LegacyTUserAccountRepository(
            JdbcTemplate jdbcTemplate,
            LegacyUserSecurityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public Optional<LegacyTUserAccount> findByUsernameIgnoreCase(String username) {
        return jdbcTemplate.query(
                        FIND_ACCOUNT_SQL,
                        (resultSet, rowNumber) -> new LegacyTUserAccount(
                                resultSet.getLong("USERID"),
                                resultSet.getString("USERNAME"),
                                resultSet.getString("USERPASS"),
                                resultSet.getInt("USERACTIVE") == 1,
                                resultSet.getInt("CAN_EDIT") == 1,
                                resultSet.getInt("CAN_PUBLISH") == 1),
                        properties.getPermitMenuId(),
                        properties.getPermitMenuId(),
                        username)
                .stream()
                .findFirst();
    }
}
