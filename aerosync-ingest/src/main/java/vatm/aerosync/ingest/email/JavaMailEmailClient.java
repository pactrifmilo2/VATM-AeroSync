package vatm.aerosync.ingest.email;



import jakarta.mail.Address;

import jakarta.mail.BodyPart;

import jakarta.mail.FetchProfile;

import jakarta.mail.Folder;

import jakarta.mail.Message;

import jakarta.mail.MessagingException;

import jakarta.mail.Multipart;

import jakarta.mail.Part;

import jakarta.mail.Session;

import jakarta.mail.Store;

import jakarta.mail.internet.InternetAddress;

import jakarta.mail.search.FromStringTerm;

import jakarta.mail.search.OrTerm;

import jakarta.mail.search.SearchTerm;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import vatm.aerosync.ingest.config.EmailProperties;

import vatm.aerosync.ingest.support.PriorityDetector;



import java.io.IOException;

import java.io.InputStream;

import java.time.LocalDateTime;

import java.time.ZoneId;

import java.util.ArrayList;

import java.util.Arrays;

import java.util.Comparator;

import java.util.Date;

import java.util.List;

import java.util.Properties;



@Component

public class JavaMailEmailClient implements EmailClient {



    private static final Logger log = LoggerFactory.getLogger(JavaMailEmailClient.class);



    private final EmailProperties emailProperties;



    public JavaMailEmailClient(EmailProperties emailProperties) {

        this.emailProperties = emailProperties;

    }



    @Override

    public List<EmailMessage> fetchMessages(int maxMessages) {

        Properties sessionProperties = new Properties();

        String protocol = emailProperties.getProtocol();

        sessionProperties.put("mail.store.protocol", protocol);

        sessionProperties.put("mail." + protocol + ".connectiontimeout",

                String.valueOf(emailProperties.getConnectionTimeoutMs()));

        sessionProperties.put("mail." + protocol + ".partialfetch", "false");



        Session session = Session.getInstance(sessionProperties);

        List<String> whitelistSenders = emailProperties.getWhitelistSenders();



        try (Store store = session.getStore(protocol)) {

            store.connect(

                    emailProperties.getHost(),

                    emailProperties.getPort(),

                    emailProperties.getUsername(),

                    emailProperties.getPassword());

            try (Folder folder = store.getFolder(emailProperties.getFolder())) {

                folder.open(Folder.READ_ONLY);

                Message[] candidates = findCandidateMessages(folder, whitelistSenders, maxMessages);

                prefetchEnvelopes(folder, candidates);

                return convertMessages(candidates, maxMessages, whitelistSenders);

            }

        } catch (MessagingException e) {

            throw new IllegalStateException("Failed to fetch email messages", e);

        }

    }



    private Message[] findCandidateMessages(Folder folder, List<String> whitelistSenders, int maxMessages)

            throws MessagingException {

        Message[] candidates;

        if (!whitelistSenders.isEmpty()) {

            candidates = folder.search(buildWhitelistSearchTerm(whitelistSenders));

            log.debug("IMAP search found {} message(s) from whitelisted senders", candidates.length);

        } else {

            candidates = folder.getMessages();

        }

        return selectMostRecent(candidates, maxMessages);

    }



    SearchTerm buildWhitelistSearchTerm(List<String> whitelistSenders) {

        SearchTerm[] terms = whitelistSenders.stream()

                .map(FromStringTerm::new)

                .toArray(SearchTerm[]::new);

        if (terms.length == 1) {

            return terms[0];

        }

        return new OrTerm(terms);

    }



    private Message[] selectMostRecent(Message[] candidates, int maxMessages) {

        if (candidates.length <= maxMessages) {

            return candidates;

        }

        Message[] sorted = Arrays.copyOf(candidates, candidates.length);

        Arrays.sort(sorted, Comparator.comparingInt(Message::getMessageNumber));

        int start = sorted.length - maxMessages;

        return Arrays.copyOfRange(sorted, start, sorted.length);

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



    List<EmailMessage> convertMessages(Message[] candidates, int limit, List<String> whitelistSenders) {

        List<EmailMessage> messages = new ArrayList<>();

        for (Message candidate : candidates) {

            if (messages.size() >= limit) {

                break;

            }

            EmailMessage parsed = parseMessage(candidate, whitelistSenders);

            if (parsed != null) {

                messages.add(parsed);

            }

        }

        return messages;

    }



    private EmailMessage parseMessage(Message message, List<String> whitelistSenders) {

        try {

            return toEmailMessage(message, false, whitelistSenders);

        } catch (MessagingException strictFailure) {

            if (whitelistSenders.isEmpty()) {

                log.warn("Skipping message #{}: failed to read IMAP envelope ({})",

                        message.getMessageNumber(), strictFailure.getMessage());

                return null;

            }

            try {

                EmailMessage recovered = toEmailMessage(message, true, whitelistSenders);

                log.warn("Recovered message #{} from whitelisted sender using fallback envelope parsing",

                        message.getMessageNumber());

                return recovered;

            } catch (MessagingException lenientFailure) {

                log.warn("Skipping message #{}: failed to read IMAP envelope ({})",

                        message.getMessageNumber(), lenientFailure.getMessage());

                return null;

            }

        }

    }



    private EmailMessage toEmailMessage(Message message, boolean lenient, List<String> whitelistSenders)

            throws MessagingException {

        String messageId = safeMessageId(message);

        String sender = lenient

                ? resolveSenderLenient(message, whitelistSenders)

                : extractSender(message);

        String subject = safeSubject(message);

        LocalDateTime receivedAt = safeReceivedAt(message);

        List<EmailAttachment> attachments = extractAttachments(message);

        boolean priority = PriorityDetector.isPriority(null, subject);

        return new EmailMessage(messageId, sender, subject, receivedAt, attachments, priority);

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



    private String resolveSenderLenient(Message message, List<String> whitelistSenders) throws MessagingException {

        try {

            return extractSender(message);

        } catch (MessagingException ex) {

            String[] fromHeader = message.getHeader("From");

            if (fromHeader != null) {

                for (String headerValue : fromHeader) {

                    for (String allowed : whitelistSenders) {

                        if (headerValue.toLowerCase().contains(allowed.toLowerCase())) {

                            return allowed;

                        }

                    }

                }

            }

            if (whitelistSenders.size() == 1) {

                return whitelistSenders.getFirst();

            }

            throw ex;

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

}


