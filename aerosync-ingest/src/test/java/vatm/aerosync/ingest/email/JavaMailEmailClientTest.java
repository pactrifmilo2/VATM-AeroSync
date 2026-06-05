package vatm.aerosync.ingest.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.ingest.config.EmailProperties;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JavaMailEmailClientTest {

    @Mock
    private Message readableMessage;

    @Mock
    private Message unreadableMessage;

    private JavaMailEmailClient emailClient;

    @BeforeEach
    void setUp() {
        emailClient = new JavaMailEmailClient(new EmailProperties());
    }

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
                new Message[] { unreadableMessage, readableMessage }, 10, List.of());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().messageId()).isEqualTo("msg-good");
        assertThat(messages.getFirst().sender()).isEqualTo("sender@example.com");
    }

    @Test
    void convertMessages_recoversAttachmentsWhenEnvelopeFailsForWhitelistedSender() throws Exception {
        EmailProperties properties = new EmailProperties();
        properties.setWhitelistSenders(List.of("haibdhe140272@fpt.edu.vn"));
        emailClient = new JavaMailEmailClient(properties);

        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setFileName("flights.csv");
        attachmentPart.setText("callsign,from,to");
        attachmentPart.setDisposition(MimeBodyPart.ATTACHMENT);

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(attachmentPart);

        when(unreadableMessage.getMessageNumber()).thenReturn(22430);
        when(unreadableMessage.getHeader("Message-ID")).thenReturn(new String[] { "msg-recover" });
        when(unreadableMessage.getFrom()).thenThrow(new MessagingException("Failed to load IMAP envelope"));
        when(unreadableMessage.getHeader(eq("From"))).thenReturn(new String[] {
                "haibdhe140272@fpt.edu.vn"
        });
        when(unreadableMessage.getSubject()).thenReturn("test");
        when(unreadableMessage.getReceivedDate()).thenReturn(new Date());
        when(unreadableMessage.getContent()).thenReturn(multipart);
        when(unreadableMessage.isMimeType("multipart/*")).thenReturn(true);

        List<EmailMessage> messages = emailClient.convertMessages(
                new Message[] { unreadableMessage }, 10, properties.getWhitelistSenders());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().sender()).isEqualTo("haibdhe140272@fpt.edu.vn");
        assertThat(messages.getFirst().attachments()).hasSize(1);
        assertThat(messages.getFirst().attachments().getFirst().fileName()).isEqualTo("flights.csv");
    }
}
