package vatm.aerosync.ingest.email;

import java.util.List;
import java.util.function.Predicate;

public interface EmailClient {

    List<EmailMessage> fetchMessages(int maxMessages);

    default List<EmailMessage> fetchMessages(int maxMessages, Predicate<EmailEnvelope> shouldDownload) {
        return fetchMessages(maxMessages).stream()
                .filter(message -> shouldDownload.test(message.envelope()))
                .toList();
    }

    default void acknowledge(EmailReference reference, EmailDisposition disposition) {
        // Optional for non-IMAP implementations and tests.
    }
}
