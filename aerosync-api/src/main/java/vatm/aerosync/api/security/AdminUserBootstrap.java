package vatm.aerosync.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.config.AdminBootstrapProperties;
import vatm.aerosync.common.entity.AppUser;
import vatm.aerosync.common.enums.UserRole;
import vatm.aerosync.common.repository.AppUserRepository;

@Component
public class AdminUserBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminUserBootstrap.class);

    private final AdminBootstrapProperties properties;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserBootstrap(AdminBootstrapProperties properties,
                              AppUserRepository appUserRepository,
                              PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!hasText(properties.getUsername()) || !hasText(properties.getPassword())) {
            return;
        }
        appUserRepository.findByUsernameIgnoreCase(properties.getUsername().trim())
                .orElseGet(() -> createAdmin(
                        properties.getUsername().trim(),
                        properties.getPassword()));
    }

    private AppUser createAdmin(String username, String password) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(UserRole.ADMIN);
        user.setEnabled(true);
        AppUser saved = appUserRepository.save(user);
        LOGGER.info("Created configured AeroSync bootstrap administrator '{}'", username);
        return saved;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
