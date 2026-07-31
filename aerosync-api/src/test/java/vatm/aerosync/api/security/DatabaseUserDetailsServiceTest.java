package vatm.aerosync.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import vatm.aerosync.common.entity.AppUser;
import vatm.aerosync.common.enums.UserRole;
import vatm.aerosync.common.repository.AppUserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseUserDetailsServiceTest {

    @Test
    void loadsAuthorityFromDatabaseRole() {
        AppUserRepository repository = mock(AppUserRepository.class);
        AppUser user = new AppUser();
        user.setUsername("operator.one");
        user.setPasswordHash("$2a$10$example");
        user.setRole(UserRole.OPERATOR);
        when(repository.findByUsernameIgnoreCase("operator.one")).thenReturn(Optional.of(user));

        UserDetails details = new DatabaseUserDetailsService(repository)
                .loadUserByUsername("operator.one");

        assertThat(details.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_OPERATOR");
    }
}
