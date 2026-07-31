package vatm.aerosync.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.legacy-users")
public class LegacyUserSecurityProperties {

    private String adminUsername = "admin";
    private long permitMenuId = 403L;

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public long getPermitMenuId() {
        return permitMenuId;
    }

    public void setPermitMenuId(long permitMenuId) {
        this.permitMenuId = permitMenuId;
    }
}
