package com.aigre.email;

import com.aigre.guardrail.PiiRedactor;
import com.aigre.intake.GrievanceIntakeRequest;
import com.aigre.workflow.GrievanceWorkflowResponse;
import com.aigre.workflow.GrievanceWorkflowService;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Properties;

/**
 * Second inbound channel for citizen grievances, alongside the web portal. Polls a monitored
 * IMAP mailbox for unread mail and feeds each one through the exact same entry point the portal
 * uses -- {@link GrievanceWorkflowService#start(GrievanceIntakeRequest, String)} -- so
 * classification, duplicate detection, human-review pause, and SLA computation all behave
 * identically regardless of channel. Off by default (email.enabled=false); see
 * docs/RUNNING.md "Email ingestion" for local setup.
 *
 * Idempotency comes from the IMAP SEEN flag, not a separate cursor table: only unseen messages
 * are ever fetched, and a message is marked SEEN only after start() succeeds. A message that
 * fails to ingest is moved to a "Failed" folder instead of retrying forever on every poll --
 * this project's "every cycle needs a termination condition you control" rule applied to a mail
 * poller rather than an LLM agent loop.
 */
@Service
public class EmailGrievancePoller {

    private static final Logger log = LoggerFactory.getLogger(EmailGrievancePoller.class);
    private static final String FAILED_FOLDER = "Failed";

    private final GrievanceWorkflowService workflowService;
    private final PiiRedactor piiRedactor;

    @Value("${email.enabled:false}")
    private boolean enabled;

    @Value("${email.imap.host:}")
    private String host;

    @Value("${email.imap.port:993}")
    private int port;

    @Value("${email.imap.protocol:imaps}")
    private String protocol;

    @Value("${email.imap.username:}")
    private String username;

    @Value("${email.imap.password:}")
    private String password;

    @Value("${email.imap.folder:INBOX}")
    private String folderName;

    public EmailGrievancePoller(GrievanceWorkflowService workflowService, PiiRedactor piiRedactor) {
        this.workflowService = workflowService;
        this.piiRedactor = piiRedactor;
    }

    @Scheduled(fixedDelayString = "${email.poll-interval-ms:60000}")
    public void poll() {
        if (!enabled) {
            return;
        }

        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        Session session = Session.getInstance(props);

        try (Store store = session.getStore(protocol)) {
            store.connect(host, port, username, password);
            Folder inbox = store.getFolder(folderName);
            inbox.open(Folder.READ_WRITE);
            try {
                Message[] unseen = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                log.info("Email poll: {} unseen message(s) in {}", unseen.length, folderName);
                for (Message message : unseen) {
                    processMessage(store, inbox, message);
                }
            } finally {
                inbox.close(true); // expunge -- removes messages moved to the Failed folder
            }
        } catch (MessagingException e) {
            log.error("Email poll failed to connect to {}:{}", host, port, e);
        }
    }

    private void processMessage(Store store, Folder inbox, Message message) {
        try {
            String citizenEmail = extractSenderEmail(message);
            String citizenName = extractSenderName(message);
            String body = extractBody(message);
            String subject = message.getSubject();
            String rawText = (subject != null && !subject.isBlank())
                    ? "Subject: " + subject.trim() + "\n\n" + body
                    : body;

            PiiRedactor.Result redacted = piiRedactor.redact(rawText);

            GrievanceIntakeRequest request =
                    new GrievanceIntakeRequest(redacted.text(), citizenName, citizenEmail, null);
            GrievanceWorkflowResponse response = workflowService.start(request, "EMAIL");

            message.setFlag(Flags.Flag.SEEN, true);
            log.info("Email-ingested grievance {} from {}", response.grievanceId(), citizenEmail);
        } catch (Exception e) {
            log.error("Failed to ingest an email message -- moving it to the {} folder", FAILED_FOLDER, e);
            moveToFailedFolder(store, inbox, message);
        }
    }

    private void moveToFailedFolder(Store store, Folder inbox, Message message) {
        try {
            Folder failedFolder = store.getFolder(FAILED_FOLDER);
            if (!failedFolder.exists()) {
                failedFolder.create(Folder.HOLDS_MESSAGES);
            }
            inbox.copyMessages(new Message[] {message}, failedFolder);
            message.setFlag(Flags.Flag.DELETED, true);
        } catch (MessagingException e) {
            log.error("Could not move failed message to the {} folder -- it will be retried next poll",
                    FAILED_FOLDER, e);
        }
    }

    private static String extractSenderEmail(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return null;
        }
        return from[0] instanceof InternetAddress address ? address.getAddress() : from[0].toString();
    }

    private static String extractSenderName(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0 || !(from[0] instanceof InternetAddress address)) {
            return null;
        }
        String personal = address.getPersonal();
        return personal != null && !personal.isBlank() ? personal : null;
    }

    private static String extractBody(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return part.getContent().toString();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            String htmlFallback = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("text/plain")) {
                    return bodyPart.getContent().toString();
                }
                if (bodyPart.isMimeType("multipart/*")) {
                    String nested = extractBody(bodyPart);
                    if (nested != null && !nested.isBlank()) {
                        return nested;
                    }
                } else if (htmlFallback == null && bodyPart.isMimeType("text/html")) {
                    htmlFallback = stripHtml(bodyPart.getContent().toString());
                }
            }
            return htmlFallback == null ? "" : htmlFallback;
        }
        if (part.isMimeType("text/html")) {
            return stripHtml(part.getContent().toString());
        }
        return "";
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
