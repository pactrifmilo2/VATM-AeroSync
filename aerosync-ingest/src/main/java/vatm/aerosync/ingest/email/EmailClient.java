package vatm.aerosync.ingest.email;

import java.util.List;

public interface EmailClient {

    List<EmailMessage> fetchMessages(int maxMessages);
}
