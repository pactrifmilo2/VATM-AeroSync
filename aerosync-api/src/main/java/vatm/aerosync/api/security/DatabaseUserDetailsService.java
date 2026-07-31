package vatm.aerosync.api.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.config.LegacyUserSecurityProperties;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final LegacyTUserAccountRepository accountRepository;
    private final LegacyUserSecurityProperties properties;

    public DatabaseUserDetailsService(
            LegacyTUserAccountRepository accountRepository,
            LegacyUserSecurityProperties properties) {
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        LegacyTUserAccount user = accountRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown AeroSync user"));

        boolean administrator = user.username().equalsIgnoreCase(
                properties.getAdminUsername())
                && user.canEditPermits()
                && user.canPublishPermits();
        boolean operator = user.canEditPermits();
        if (!administrator && !operator) {
            throw new UsernameNotFoundException(
                    "User is not authorized for AeroSync permit review");
        }

        return User.withUsername(user.username())
                .password(user.passwordHash())
                .roles(administrator ? "ADMIN" : "OPERATOR")
                .disabled(!user.active())
                .build();
    }
}
