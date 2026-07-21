package vatm.aerosync.ingest.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Folder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.SearchTerm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.ingest.config.EmailProperties;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class JavaMailEmailClientTest {

    @Mock
    private Message readableMessage;

    @Mock
    private Message unreadableMessage;

    private final EmailProperties emailProperties = new EmailProperties();
    private final JavaMailEmailClient emailClient = new JavaMailEmailClient(emailProperties);

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

    @Test
    void convertMessages_recoversUnreadableEnvelopeFromRawHeaders() throws Exception {
        Message recovered = mock(Message.class);
        when(recovered.getMessageNumber()).thenReturn(99);
        when(recovered.getHeader("Message-ID")).thenReturn(new String[] { "msg-recovered" });
        when(recovered.getFrom()).thenThrow(new MessagingException("Failed to load IMAP envelope"));
        when(recovered.getHeader("From")).thenReturn(new String[] { "Operations <ops@example.com>" });
        when(recovered.getHeader("Subject")).thenReturn(new String[] { "Today's permit" });
        when(recovered.getHeader("Date")).thenReturn(
                new String[] { "Tue, 21 Jul 2026 15:00:00 +0700" });
        when(recovered.getContent()).thenReturn("body");
        when(recovered.isMimeType("multipart/*")).thenReturn(false);
        when(recovered.isMimeType("text/plain")).thenReturn(true);

        List<EmailMessage> messages = emailClient.convertMessages(
                new Message[] { recovered }, 10);

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.messageId()).isEqualTo("msg-recovered");
            assertThat(message.sender()).isEqualTo("ops@example.com");
            assertThat(message.subject()).isEqualTo("Today's permit");
        });
    }

    @Test
    void convertMessages_checksCheckpointBeforeDownloadingContent() throws Exception {
        when(readableMessage.getHeader("Message-ID")).thenReturn(new String[] { "msg-known" });
        when(readableMessage.getFrom()).thenReturn(new InternetAddress[] {
                new InternetAddress("sender@example.com")
        });
        when(readableMessage.getSubject()).thenReturn("Flight data");
        when(readableMessage.getReceivedDate()).thenReturn(new Date());
        when(readableMessage.getMessageNumber()).thenReturn(55);

        List<EmailMessage> messages = emailClient.convertMessages(
                new Message[] { readableMessage },
                10,
                envelope -> false,
                "INBOX",
                null,
                0L);

        assertThat(messages).isEmpty();
        verify(readableMessage, never()).getContent();
    }

    @Test
    void convertMessages_processesNewestReceivedMailFirst() throws Exception {
        emailProperties.setOldestMessagesPerCycle(0);
        Message oldest = mock(Message.class);
        Message middle = mock(Message.class);
        Message newest = mock(Message.class);
        stubReadable(oldest, "oldest", 1, 1_000L);
        stubReadable(middle, "middle", 2, 2_000L);
        stubReadable(newest, "newest", 3, 3_000L);

        List<EmailMessage> messages = emailClient.convertMessages(
                new Message[] { oldest, newest, middle }, 2);

        assertThat(messages).extracting(EmailMessage::messageId)
                .containsExactly("newest", "middle");
    }

    @Test
    void convertMessages_doesNotLetOldUrgentMailJumpAheadOfNewMail() throws Exception {
        emailProperties.setOldestMessagesPerCycle(0);
        Message oldUrgent = mock(Message.class);
        Message newNormal = mock(Message.class);
        stubReadable(oldUrgent, "old-urgent", "URGENT old permit", 1, 1_000L);
        stubReadable(newNormal, "new-normal", "Today's permit", 2, 2_000L);

        List<EmailMessage> messages = emailClient.convertMessages(
                new Message[] { oldUrgent, newNormal }, 1);

        assertThat(messages).extracting(EmailMessage::messageId)
                .containsExactly("new-normal");
    }

    @Test
    void findCandidateMessages_scansBoundedRecentAndBacklogWindows() throws Exception {
        emailProperties.setMailboxScanWindowSize(200);
        emailProperties.setOldestMessagesPerCycle(5);
        Folder folder = mock(Folder.class);
        Message recent = mock(Message.class);
        Message backlog = mock(Message.class);
        when(folder.getMessageCount()).thenReturn(10_000);
        when(folder.getMessages(9_801, 10_000)).thenReturn(new Message[] { recent });
        when(folder.getMessages(1, 50)).thenReturn(new Message[] { backlog });
        when(folder.search(any(SearchTerm.class))).thenReturn(new Message[0]);

        Message[] candidates = emailClient.findCandidateMessages(folder, 100);

        assertThat(candidates).containsExactly(recent, backlog);
        verify(folder).getMessageCount();
        verify(folder).getMessages(9_801, 10_000);
        verify(folder).getMessages(1, 50);
        verify(folder).search(any(SearchTerm.class));
        verifyNoMoreInteractions(folder);
    }

    @Test
    void findCandidateMessages_includesReceivedTodaySearchOutsideSequenceWindow() throws Exception {
        emailProperties.setMailboxScanWindowSize(200);
        emailProperties.setOldestMessagesPerCycle(0);
        Folder folder = mock(Folder.class);
        Message today = mock(Message.class);
        Message recentBySequence = mock(Message.class);
        when(folder.getMessageCount()).thenReturn(10_000);
        when(folder.getMessages(9_801, 10_000)).thenReturn(new Message[] { recentBySequence });
        when(folder.search(any(SearchTerm.class))).thenReturn(new Message[] { today });

        Message[] candidates = emailClient.findCandidateMessages(folder, 100);

        assertThat(candidates).containsExactly(today, recentBySequence);
    }

    private void stubReadable(Message message, String messageId, int messageNumber, long receivedAt)
            throws Exception {
        stubReadable(message, messageId, "Flight data", messageNumber, receivedAt);
    }

    private void stubReadable(Message message,
                              String messageId,
                              String subject,
                              int messageNumber,
                              long receivedAt) throws Exception {
        when(message.getHeader("Message-ID")).thenReturn(new String[] { messageId });
        when(message.getFrom()).thenReturn(new InternetAddress[] {
                new InternetAddress("sender@example.com")
        });
        when(message.getSubject()).thenReturn(subject);
        when(message.getReceivedDate()).thenReturn(new Date(receivedAt));
        when(message.getMessageNumber()).thenReturn(messageNumber);
        lenient().when(message.getContent()).thenReturn("body");
        lenient().when(message.isMimeType("multipart/*")).thenReturn(false);
        lenient().when(message.isMimeType("text/plain")).thenReturn(true);
    }
}
