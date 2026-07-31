package vatm.aerosync.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import vatm.aerosync.api.config.LegacyUserSecurityProperties;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseUserDetailsServiceTest {

    @Test
    void mapsMainPublisherToAdministrator() {
        LegacyTUserAccountRepository repository =
                mock(LegacyTUserAccountRepository.class);
        LegacyUserSecurityProperties properties =
                new LegacyUserSecurityProperties();
        LegacyTUserAccount user = new LegacyTUserAccount(
                1L,
                "admin",
                "legacy-hash",
                true,
                true,
                true);
        when(repository.findByUsernameIgnoreCase("admin"))
                .thenReturn(Optional.of(user));

        UserDetails details =
                new DatabaseUserDetailsService(repository, properties)
                        .loadUserByUsername("admin");

        assertThat(details.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void mapsOtherPermitEditorToOperator() {
        LegacyTUserAccountRepository repository =
                mock(LegacyTUserAccountRepository.class);
        LegacyUserSecurityProperties properties =
                new LegacyUserSecurityProperties();
        when(repository.findByUsernameIgnoreCase("operator.one"))
                .thenReturn(Optional.of(new LegacyTUserAccount(
                        2L,
                        "operator.one",
                        "legacy-hash",
                        true,
                        true,
                        false)));

        UserDetails details =
                new DatabaseUserDetailsService(repository, properties)
                        .loadUserByUsername("operator.one");

        assertThat(details.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void refusesUserWithoutPermitEditPermission() {
        LegacyTUserAccountRepository repository =
                mock(LegacyTUserAccountRepository.class);
        LegacyUserSecurityProperties properties =
                new LegacyUserSecurityProperties();
        when(repository.findByUsernameIgnoreCase("viewer.one"))
                .thenReturn(Optional.of(new LegacyTUserAccount(
                        3L,
                        "viewer.one",
                        "legacy-hash",
                        true,
                        false,
                        false)));

        assertThatThrownBy(() ->
                new DatabaseUserDetailsService(repository, properties)
                        .loadUserByUsername("viewer.one"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
