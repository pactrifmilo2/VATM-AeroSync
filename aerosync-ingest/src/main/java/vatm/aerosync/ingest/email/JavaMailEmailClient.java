package vatm.aerosync.ingest.email;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vatm.aerosync.ingest.config.EmailProperties;
import vatm.aerosync.ingest.support.PriorityDetector;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.function.Predicate;

@Component
public class JavaMailEmailClient implements EmailClient {

    private static final Logger log = LoggerFactory.getLogger(JavaMailEmailClient.class);

    private final EmailProperties emailProperties;

    public JavaMailEmailClient(EmailProperties emailProperties) {
        this.emailProperties = emailProperties;
    }

    @Override
    public List<EmailMessage> fetchMessages(int maxMessages) {
        return fetchMessages(maxMessages, envelope -> true);
    }

    @Override
    public List<EmailMessage> fetchMessages(int maxMessages, Predicate<EmailEnvelope> shouldDownload) {
        if (maxMessages <= 0) {
            return List.of();
        }
        Properties sessionProperties = new Properties();
        String protocol = emailProperties.getProtocol();
        sessionProperties.put("mail.store.protocol", protocol);
        sessionProperties.put("mail." + protocol + ".connectiontimeout",
                String.valueOf(emailProperties.getConnectionTimeoutMs()));
        sessionProperties.put("mail." + protocol + ".partialfetch", "false");

        Session session = Session.getInstance(sessionProperties);

        try (Store store = session.getStore(protocol)) {
            store.connect(
                    emailProperties.getHost(),
                    emailProperties.getPort(),
                    emailProperties.getUsername(),
                    emailProperties.getPassword());
            try (Folder folder = store.getFolder(emailProperties.getFolder())) {
                folder.open(Folder.READ_ONLY);
                Message[] candidates = findCandidateMessages(folder, maxMessages);
                prefetchEnvelopes(folder, candidates);
                UIDFolder uidFolder = folder instanceof UIDFolder candidate ? candidate : null;
                long uidValidity = uidFolder != null ? uidFolder.getUIDValidity() : 0L;
                return convertMessages(
                        candidates,
                        maxMessages,
                        shouldDownload,
                        folder.getFullName(),
                        uidFolder,
                        uidValidity);
            }
        } catch (MessagingException e) {
            throw new IllegalStateException("Failed to fetch email messages", e);
        }
    }

    Message[] findCandidateMessages(Folder folder, int maxMessages) throws MessagingException {
        int messageCount = folder.getMessageCount();
        if (messageCount == 0) {
            return new Message[0];
        }

        int recentWindowSize = Math.min(
                messageCount,
                Math.max(maxMessages, emailProperties.getMailboxScanWindowSize()));
        int recentStart = messageCount - recentWindowSize + 1;
        Message[] recent = folder.getMessages(recentStart, messageCount);

        Message[] receivedToday = findMessagesReceivedToday(folder, recentWindowSize);

        int requestedBacklog = Math.max(0, emailProperties.getOldestMessagesPerCycle());
        int backlogWindowSize = Math.min(
                Math.max(0, recentStart - 1),
                requestedBacklog == 0 ? 0 : Math.max(requestedBacklog, requestedBacklog * 10));
        if (backlogWindowSize == 0) {
            Message[] candidates = mergeDistinct(receivedToday, recent);
            log.debug("Scanning {} received-today and {} recent message(s) from mailbox containing {} message(s)",
                    receivedToday.length, recent.length, messageCount);
            return candidates;
        }

        Message[] backlog = folder.getMessages(1, backlogWindowSize);
        Message[] candidates = mergeDistinct(receivedToday, recent, backlog);
        log.debug("Scanning {} received-today, {} recent, and {} backlog message(s) "
                        + "from mailbox containing {} message(s)",
                receivedToday.length, recent.length, backlog.length, messageCount);
        return candidates;
    }

    private Message[] findMessagesReceivedToday(Folder folder, int limit) {
        try {
            Date startOfToday = Date.from(
                    LocalDate.now()
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant());
            Message[] matches = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, startOfToday));
            if (matches.length <= limit) {
                return matches;
            }
            return Arrays.copyOfRange(matches, matches.length - limit, matches.length);
        } catch (MessagingException exception) {
            log.warn("IMAP received-date search failed; using bounded sequence window ({})",
                    exception.getMessage());
            return new Message[0];
        }
    }

    private Message[] mergeDistinct(Message[]... groups) {
        LinkedHashSet<Message> merged = new LinkedHashSet<>();
        for (Message[] group : groups) {
            merged.addAll(Arrays.asList(group));
        }
        return merged.toArray(Message[]::new);
    }

    private void prefetchEnvelopes(Folder folder, Message[] messages) throws MessagingException {
        if (messages.length == 0) {
            return;
        }
        FetchProfile fetchProfile = new FetchProfile();
        fetchProfile.add(FetchProfile.Item.ENVELOPE);
        fetchProfile.add(FetchProfile.Item.FLAGS);
        fetchProfile.add(FetchProfile.Item.CONTENT_INFO);
        folder.fetch(messages, fetchProfile);
    }

    List<EmailMessage> convertMessages(Message[] candidates, int limit) {
        return convertMessages(
                candidates,
                limit,
                envelope -> true,
                emailProperties.getFolder(),
                null,
                0L);
    }

    List<EmailMessage> convertMessages(Message[] candidates,
                                       int limit,
                                       Predicate<EmailEnvelope> shouldDownload,
                                       String mailboxFolder,
                                       UIDFolder uidFolder,
                                       long uidValidity) {
        List<MailCandidate> eligible = Arrays.stream(candidates)
                .map(message -> parseEnvelope(message, mailboxFolder, uidFolder, uidValidity))
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> shouldDownload.test(candidate.envelope()))
                .sorted(newestFirst())
                .toList();

        List<MailCandidate> selected = selectWithBacklogCapacity(eligible, limit);
        List<EmailMessage> messages = new ArrayList<>();
        for (MailCandidate candidate : selected) {
            EmailMessage parsed = parseMessage(candidate);
            if (parsed != null) {
                messages.add(parsed);
            }
        }
        return messages;
    }

    private Comparator<MailCandidate> newestFirst() {
        return Comparator.comparing(
                        (MailCandidate candidate) -> candidate.envelope().receivedAt(),
                        Comparator.reverseOrder())
                .thenComparing(
                        candidate -> candidate.envelope().priority(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.envelope().messageUid(), Comparator.reverseOrder());
    }

    private List<MailCandidate> selectWithBacklogCapacity(List<MailCandidate> eligible, int limit) {
        if (eligible.size() <= limit) {
            return eligible;
        }
        int oldestSlots = Math.min(
                Math.max(0, emailProperties.getOldestMessagesPerCycle()),
                Math.max(0, limit - 1));
        int newestSlots = limit - oldestSlots;
        List<MailCandidate> selected = new ArrayList<>(eligible.subList(0, newestSlots));
        eligible.subList(newestSlots, eligible.size()).stream()
                .sorted(Comparator
                        .comparing((MailCandidate candidate) -> candidate.envelope().receivedAt())
                        .thenComparing(candidate -> candidate.envelope().messageUid()))
                .limit(oldestSlots)
                .forEach(selected::add);
        return selected;
    }

    private MailCandidate parseEnvelope(Message message,
                                        String mailboxFolder,
                                        UIDFolder uidFolder,
                                        long uidValidity) {
        try {
            String messageId = safeMessageId(message);
            String sender = extractSender(message);
            String subject = safeSubject(message);
            LocalDateTime receivedAt = safeReceivedAt(message);
            long messageUid = uidFolder != null ? uidFolder.getUID(message) : message.getMessageNumber();
            boolean priority = PriorityDetector.isPriority(null, subject);
            EmailEnvelope envelope = new EmailEnvelope(
                    messageId,
                    sender,
                    subject,
                    receivedAt,
                    mailboxFolder,
                    uidValidity,
                    messageUid,
                    priority);
            return new MailCandidate(message, envelope);
        } catch (MessagingException e) {
            MailCandidate fallback = parseEnvelopeFromHeaders(
                    message, mailboxFolder, uidFolder, uidValidity);
            if (fallback != null) {
                log.debug("Recovered message #{} from raw headers after IMAP envelope failure ({})",
                        message.getMessageNumber(), e.getMessage());
                return fallback;
            }
            log.warn("Skipping message #{}: failed to read IMAP envelope and headers ({})",
                    message.getMessageNumber(), e.getMessage());
            return null;
        }
    }

    private MailCandidate parseEnvelopeFromHeaders(Message message,
                                                   String mailboxFolder,
                                                   UIDFolder uidFolder,
                                                   long uidValidity) {
        try {
            String messageId = firstHeader(message, "Message-ID");
            if (messageId == null || messageId.isBlank()) {
                messageId = String.valueOf(message.getMessageNumber());
            }
            String rawFrom = firstHeader(message, "From");
            String rawSubject = firstHeader(message, "Subject");
            String rawDate = firstHeader(message, "Date");
            if (rawFrom == null && rawSubject == null && rawDate == null) {
                return null;
            }
            String sender = senderFromHeader(rawFrom);
            String subject = decodeHeader(rawSubject);
            LocalDateTime receivedAt = dateFromHeader(rawDate);
            long messageUid = uidFolder != null ? uidFolder.getUID(message) : message.getMessageNumber();
            EmailEnvelope envelope = new EmailEnvelope(
                    messageId,
                    sender,
                    subject,
                    receivedAt,
                    mailboxFolder,
                    uidValidity,
                    messageUid,
                    PriorityDetector.isPriority(null, subject));
            return new MailCandidate(message, envelope);
        } catch (MessagingException | RuntimeException fallbackFailure) {
            return null;
        }
    }

    private String firstHeader(Message message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private String senderFromHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            InternetAddress[] addresses = InternetAddress.parseHeader(raw, false);
            return addresses.length == 0 ? raw : addresses[0].getAddress();
        } catch (jakarta.mail.internet.AddressException exception) {
            return raw;
        }
    }

    private String decodeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        try {
            return MimeUtility.decodeText(raw);
        } catch (java.io.UnsupportedEncodingException exception) {
            return raw;
        }
    }

    private LocalDateTime dateFromHeader(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .withZoneSameInstant(ZoneId.systemDefault())
                        .toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                // A current timestamp keeps an unreadable recent envelope eligible; UID breaks ties.
            }
        }
        return LocalDateTime.now();
    }

    private EmailMessage parseMessage(MailCandidate candidate) {
        try {
            Message message = candidate.message();
            EmailEnvelope envelope = candidate.envelope();
            List<EmailAttachment> attachments = extractAttachments(message);
            String body = extractBody(message);
            return new EmailMessage(
                    envelope.messageId(),
                    envelope.sender(),
                    envelope.subject(),
                    envelope.receivedAt(),
                    attachments,
                    envelope.priority(),
                    body,
                    envelope.mailboxFolder(),
                    envelope.uidValidity(),
                    envelope.messageUid());
        } catch (MessagingException e) {
            log.warn("Skipping message #{}: failed to read IMAP envelope ({})",
                    candidate.message().getMessageNumber(), e.getMessage());
            return null;
        }
    }

    @Override
    public void acknowledge(EmailReference reference, EmailDisposition disposition) {
        Properties sessionProperties = new Properties();
        String protocol = emailProperties.getProtocol();
        sessionProperties.put("mail.store.protocol", protocol);
        sessionProperties.put("mail." + protocol + ".connectiontimeout",
                String.valueOf(emailProperties.getConnectionTimeoutMs()));
        Session session = Session.getInstance(sessionProperties);

        try (Store store = session.getStore(protocol)) {
            store.connect(
                    emailProperties.getHost(),
                    emailProperties.getPort(),
                    emailProperties.getUsername(),
                    emailProperties.getPassword());
            String destinationName = disposition == EmailDisposition.PROCESSED
                    ? emailProperties.getProcessedFolder()
                    : emailProperties.getErrorFolder();
            Folder destination = ensureDestinationFolder(store, destinationName);
            List<String> sourceNames = new ArrayList<>();
            sourceNames.add(reference.mailboxFolder());
            sourceNames.add(disposition == EmailDisposition.PROCESSED
                    ? emailProperties.getErrorFolder()
                    : emailProperties.getProcessedFolder());
            for (String sourceName : sourceNames.stream().distinct().toList()) {
                if (sourceName.equals(destinationName)) {
                    continue;
                }
                if (moveFromSource(store, sourceName, destination, reference)) {
                    return;
                }
            }
            log.info("Email {} is no longer in a source mailbox; acknowledgement is already satisfied",
                    reference.messageId());
        } catch (MessagingException e) {
            throw new IllegalStateException(
                    "Failed to acknowledge email %s".formatted(reference.messageId()), e);
        }
    }

    private boolean moveFromSource(Store store,
                                   String sourceName,
                                   Folder destination,
                                   EmailReference reference) throws MessagingException {
        Folder source = store.getFolder(sourceName);
        if (!source.exists()) {
            return false;
        }
        boolean expunge = false;
        try {
            source.open(Folder.READ_WRITE);
            EmailReference lookup = sourceName.equals(reference.mailboxFolder())
                    ? reference
                    : new EmailReference(reference.messageId(), sourceName, 0L, 0L);
            Message message = findMessage(source, lookup);
            if (message == null) {
                return false;
            }
            source.copyMessages(new Message[] {message}, destination);
            message.setFlag(Flags.Flag.DELETED, true);
            expunge = true;
            return true;
        } finally {
            if (source.isOpen()) {
                source.close(expunge);
            }
        }
    }

    private Message findMessage(Folder source, EmailReference reference) throws MessagingException {
        if (source instanceof UIDFolder uidFolder
                && reference.messageUid() > 0
                && (reference.uidValidity() == 0 || uidFolder.getUIDValidity() == reference.uidValidity())) {
            Message byUid = uidFolder.getMessageByUID(reference.messageUid());
            if (byUid != null) {
                return byUid;
            }
        }
        if (reference.messageId() == null || reference.messageId().isBlank()) {
            return null;
        }
        Message[] matches = source.search(new HeaderTerm("Message-ID", reference.messageId()));
        return matches.length == 0 ? null : matches[0];
    }

    private Folder ensureDestinationFolder(Store store, String folderName) throws MessagingException {
        Folder destination = store.getFolder(folderName);
        if (destination.exists()) {
            return destination;
        }
        ensureParentFolders(destination.getParent());
        if (!destination.create(Folder.HOLDS_MESSAGES)) {
            throw new MessagingException("Could not create mailbox folder " + folderName);
        }
        return destination;
    }

    private void ensureParentFolders(Folder folder) throws MessagingException {
        if (folder == null || folder.exists() || folder.getFullName().isBlank()) {
            return;
        }
        ensureParentFolders(folder.getParent());
        if (!folder.create(Folder.HOLDS_FOLDERS)) {
            throw new MessagingException("Could not create mailbox folder " + folder.getFullName());
        }
    }

    private String safeMessageId(Message message) throws MessagingException {
        String[] headers = message.getHeader("Message-ID");
        if (headers != null && headers.length > 0) {
            return headers[0];
        }
        return String.valueOf(message.getMessageNumber());
    }

    private String safeSubject(Message message) {
        try {
            return message.getSubject() != null ? message.getSubject() : "";
        } catch (MessagingException ex) {
            return "";
        }
    }

    private LocalDateTime safeReceivedAt(Message message) {
        try {
            return toLocalDateTime(message.getReceivedDate());
        } catch (MessagingException ex) {
            return LocalDateTime.now();
        }
    }

    private String extractSender(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return "";
        }
        if (from[0] instanceof InternetAddress internetAddress) {
            return internetAddress.getAddress();
        }
        return from[0].toString();
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private List<EmailAttachment> extractAttachments(Part part) throws MessagingException {
        List<EmailAttachment> attachments = new ArrayList<>();
        Object content;
        try {
            content = part.getContent();
        } catch (IOException e) {
            throw new MessagingException("Failed to read message content", e);
        }
        if (part.isMimeType("multipart/*") && content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                collectAttachment(bodyPart, attachments);
            }
        }
        return attachments;
    }

    private String extractBody(Part part) throws MessagingException {
        try {
            Object content = part.getContent();
            if (part.isMimeType("text/plain")) {
                return content.toString();
            }
            if (part.isMimeType("text/html")) {
                return stripHtml(content.toString());
            }
            if (content instanceof Multipart multipart) {
                // Prefer text/plain, fall back to text/html then first text/*
                String htmlFallback = null;
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart bodyPart = multipart.getBodyPart(i);
                    if (bodyPart.isMimeType("text/plain")) {
                        return bodyPart.getContent().toString();
                    }
                    if (bodyPart.isMimeType("text/html") && htmlFallback == null) {
                        htmlFallback = stripHtml(bodyPart.getContent().toString());
                    }
                }
                if (htmlFallback != null) {
                    return htmlFallback;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to extract email body: {}", e.getMessage());
        }
        return "";
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        // Remove <style> and <script> blocks with their content
        String stripped = html.replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<script[^>]*>.*?</script>", "");
        // Replace <br> and <p> with newlines before removing tags
        stripped = stripped.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)<p[^>]*>", "");
        // Remove all remaining HTML tags
        stripped = stripped.replaceAll("<[^>]+>", "");
        // Decode common HTML entities
        stripped = stripped.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        // Collapse whitespace but preserve paragraph breaks
        stripped = stripped.replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("(?m)[ \\t]+$", "");
        return stripped.trim();
    }

    private void collectAttachment(BodyPart bodyPart, List<EmailAttachment> attachments) throws MessagingException {
        if (bodyPart.isMimeType("multipart/*")) {
            attachments.addAll(extractAttachments(bodyPart));
            return;
        }
        if (isAttachmentPart(bodyPart)) {
            String fileName = bodyPart.getFileName() != null ? bodyPart.getFileName() : "attachment.csv";
            attachments.add(new EmailAttachment(fileName, readBytes(bodyPart)));
        }
    }

    private boolean isAttachmentPart(BodyPart bodyPart) throws MessagingException {
        if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) {
            return true;
        }
        if (bodyPart.getFileName() != null) {
            return true;
        }
        return bodyPart.isMimeType("text/csv") || bodyPart.isMimeType("application/csv");
    }

    private byte[] readBytes(BodyPart bodyPart) throws MessagingException {
        try (InputStream input = bodyPart.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new MessagingException("Failed to read attachment", e);
        }
    }

    private record MailCandidate(Message message, EmailEnvelope envelope) {
    }
}
