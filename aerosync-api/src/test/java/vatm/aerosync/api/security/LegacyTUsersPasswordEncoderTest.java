package vatm.aerosync.api.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyTUsersPasswordEncoderTest {

    private final LegacyTUsersPasswordEncoder encoder =
            new LegacyTUsersPasswordEncoder();

    @Test
    void reproducesDotNetUnicodeMd5BitConverterFormat() {
        assertThat(encoder.encode("admin"))
                .isEqualTo("19-A2-85-41-44-B6-3A-8F-76-17-A6-F2-25-01-9B-12");
    }

    @Test
    void matchesExistingHashWithoutCaseSensitivity() {
        assertThat(encoder.matches(
                "admin",
                "19-a2-85-41-44-b6-3a-8f-76-17-a6-f2-25-01-9b-12"))
                .isTrue();
        assertThat(encoder.matches("wrong", encoder.encode("admin")))
                .isFalse();
    }
}
