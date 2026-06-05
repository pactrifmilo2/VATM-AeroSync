package vatm.aerosync.ingest.email;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vatm.aerosync.ingest.config.EmailProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
class JavaMailEmailClientLiveTest {

    private JavaMailEmailClient emailClient;

    @BeforeEach
    void setUp() {
        EmailProperties properties = new EmailProperties();
        properties.setHost(env("APP_EMAIL_HOST", "localhost"));
        properties.setPort(intEnv("APP_EMAIL_PORT", 993));
        properties.setProtocol(env("APP_EMAIL_PROTOCOL", "imaps"));
        properties.setUsername(env("APP_EMAIL_USERNAME", ""));
        properties.setPassword(env("APP_EMAIL_PASSWORD", ""));
        properties.setFolder(env("APP_EMAIL_FOLDER", "INBOX"));

        Assumptions.assumeFalse(
                properties.getPassword() == null || properties.getPassword().isBlank(),
                "APP_EMAIL_PASSWORD not set — skipping live IMAP test");
        Assumptions.assumeFalse(
                properties.getUsername() == null || properties.getUsername().isBlank(),
                "APP_EMAIL_USERNAME not set — skipping live IMAP test");

        emailClient = new JavaMailEmailClient(properties);
    }

    @Test
    void canConnectAndReadRecentInboxMessages() {
        List<EmailMessage> messages = emailClient.fetchMessages(5);

        assertThat(messages).isNotNull();
        System.out.println("IMAP connection OK — fetched " + messages.size() + " message(s) from INBOX");
        for (EmailMessage message : messages) {
            System.out.printf(
                    "  - from=%s | subject=%s | attachments=%d | received=%s%n",
                    message.sender(),
                    message.subject(),
                    message.attachments().size(),
                    message.receivedAt());
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }
}
