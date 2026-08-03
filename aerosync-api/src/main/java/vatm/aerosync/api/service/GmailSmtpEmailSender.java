package vatm.aerosync.api.service;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.Authenticator;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import org.springframework.stereotype.Component;
import vatm.aerosync.api.config.EmailResendProperties;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;

@Component
public class GmailSmtpEmailSender implements OutboundEmailSender {

    private final EmailResendProperties properties;

    public GmailSmtpEmailSender(EmailResendProperties properties) {
        this.properties = properties;
    }

    @Override
    public SendResult send(OutboundEmail email) {
        validateConfiguration();
        String recipient = hasText(email.recipient()) ? email.recipient().trim() : properties.getRecipient();
        if (!hasText(recipient)) {
            throw new IllegalStateException("APP_EMAIL_RESEND_RECIPIENT is not configured");
        }

        Properties sessionProperties = new Properties();
        sessionProperties.put("mail.smtp.auth", "true");
        sessionProperties.put("mail.smtp.starttls.enable", Boolean.toString(properties.isStarttls()));
        sessionProperties.put("mail.smtp.starttls.required", Boolean.toString(properties.isStarttls()));
        sessionProperties.put("mail.smtp.host", properties.getSmtpHost());
        sessionProperties.put("mail.smtp.port", Integer.toString(properties.getSmtpPort()));
        sessionProperties.put("mail.smtp.connectiontimeout", Integer.toString(properties.getTimeoutMs()));
        sessionProperties.put("mail.smtp.timeout", Integer.toString(properties.getTimeoutMs()));
        sessionProperties.put("mail.smtp.writetimeout", Integer.toString(properties.getTimeoutMs()));
        sessionProperties.put("mail.smtp.ssl.trust", properties.getSmtpHost());

        Session session = Session.getInstance(sessionProperties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        properties.getUsername(),
                        properties.getPassword().replace(" ", ""));
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(properties.getUsername()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, false));
            message.setSubject(nullToEmpty(email.subject()), StandardCharsets.UTF_8.name());
            message.setSentDate(new Date());
            email.headers().forEach((name, value) -> setHeader(message, name, value));

            MimeMultipart multipart = new MimeMultipart();
            MimeBodyPart bodyPart = new MimeBodyPart();
            bodyPart.setText(nullToEmpty(email.body()), StandardCharsets.UTF_8.name());
            multipart.addBodyPart(bodyPart);
            for (Attachment attachment : email.attachments()) {
                MimeBodyPart attachmentPart = new MimeBodyPart();
                attachmentPart.setDataHandler(new DataHandler(new FileDataSource(attachment.path().toFile())));
                attachmentPart.setFileName(MimeUtility.encodeText(
                        attachment.fileName(), StandardCharsets.UTF_8.name(), null));
                multipart.addBodyPart(attachmentPart);
            }
            message.setContent(multipart);
            message.saveChanges();
            Transport.send(message);
            return new SendResult(message.getMessageID(), recipient);
        } catch (AuthenticationFailedException exception) {
            throw new IllegalStateException(
                    "Gmail SMTP authentication failed; use a 16-character Gmail App Password instead of the normal account password",
                    exception);
        } catch (MessagingException | UnsupportedEncodingException exception) {
            throw new IllegalStateException("Gmail SMTP could not send the email", exception);
        }
    }

    private void validateConfiguration() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Email resend is disabled; set APP_EMAIL_RESEND_ENABLED=true after configuring Gmail SMTP");
        }
        if (!hasText(properties.getUsername()) || !hasText(properties.getPassword())) {
            throw new IllegalStateException(
                    "Gmail SMTP credentials are missing; configure APP_EMAIL_RESEND_USERNAME and APP_EMAIL_RESEND_PASSWORD");
        }
        if (!hasText(properties.getSmtpHost())) {
            throw new IllegalStateException("APP_EMAIL_RESEND_SMTP_HOST is not configured");
        }
    }

    private void setHeader(MimeMessage message, String name, String value) {
        try {
            message.setHeader(name, sanitizeHeader(value));
        } catch (MessagingException exception) {
            throw new IllegalStateException("Could not set resend email header", exception);
        }
    }

    private String sanitizeHeader(String value) {
        return nullToEmpty(value).replace("\r", " ").replace("\n", " ");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
