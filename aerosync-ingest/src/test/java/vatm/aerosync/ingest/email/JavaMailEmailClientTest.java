package vatm.aerosync.ingest.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.ingest.config.EmailProperties;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JavaMailEmailClientTest {

    @Mock
    private Message readableMessage;

    @Mock
    private Message unreadableMessage;

    private final JavaMailEmailClient emailClient = new JavaMailEmailClient(new EmailProperties());

    @Test
    void convertMessages_skipsUnreadableEnvelopeAndReturnsOthers() throws Exception {
        when(readableMessage.getHeader("Message-ID")).thenReturn(new String[] { "msg-good" });
        when(readableMessage.getFrom()).thenReturn(new InternetAddress[] {
                new InternetAddress("sender@example.com")
        });
        when(readableMessage.getSubject()).thenReturn("Flight data");
        when(readableMessage.getReceivedDate()).thenReturn(new Date());
        when(readableMessage.getContent()).thenReturn("body");
        when(readableMessage.isMimeType("multipart/*")).thenReturn(false);

        when(unreadableMessage.getMessageNumber()).thenReturn(2);
        when(unreadableMessage.getHeader("Message-ID")).thenReturn(new String[] { "msg-bad" });
        when(unreadableMessage.getFrom()).thenThrow(new MessagingException("Failed to load IMAP envelope"));

        List<EmailMessage> messages = emailClient.convertMessages(
                new Message[] { unreadableMessage, readableMessage }, 10);

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().messageId()).isEqualTo("msg-good");
        assertThat(messages.getFirst().sender()).isEqualTo("sender@example.com");
    }

    @Test
    void convertMessages_skipsUnreadableEnvelopeWithoutRecovery() throws Exception {
        when(unreadableMessage.getMessageNumber()).thenReturn(22430);
        when(unreadableMessage.getHeader("Message-ID")).thenReturn(new String[] { "msg-bad" });
        when(unreadableMessage.getFrom()).thenThrow(new MessagingException("Failed to load IMAP envelope"));

        List<EmailMessage> messages = emailClient.convertMessages(
                new Message[] { unreadableMessage }, 10);

        assertThat(messages).isEmpty();
    }
}
