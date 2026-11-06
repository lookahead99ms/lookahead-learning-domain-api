package com.lookahead.learning.content.service;

import com.lookahead.domain.Events;
import com.lookahead.domain.review.platform.PlatformReviewModels;
import com.lookahead.domain.review.platform.PlatformReviewService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * A learner's support request: Report a problem, Share feedback or Ask a question. DLV-971 (owner, 2026-11-06): the
 * request is recorded in the platform review store first, next to the review items, so the owner and admins see it under
 * Team Reviews; then it is emailed to the support inbox. The receipt row in support_receipts is unchanged.
 * Receipt statuses: "accepted" (email sent), "unconfirmed" (reserved, email not confirmed), "recorded" (recorded for
 * Team Reviews, mail is not configured so no email went).
 */
@Service
@Profile("accounts")
public class SupportFeedbackService {
    public record Attachment(String name, String data) {}
    public record Feedback(String type, String subject, String message, String replyTo, List<Attachment> images) {}
    public record Receipt(String reference, String status) {}
    private record Image(String name, byte[] bytes) {}
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final String inbox;
    private final SupportReceiptStore receipts;
    private final PlatformReviewService reviews;
    public SupportFeedbackService(ObjectProvider<JavaMailSender> sender,
            @Value("${app.support.from:}") String from, @Value("${app.support.inbox:}") String inbox, SupportReceiptStore receipts,
            PlatformReviewService reviews) {
        this.sender = sender; this.from = from; this.inbox = inbox; this.receipts = receipts; this.reviews = reviews;
    }
    public Receipt submit(AccountPrincipal actor, String key, Feedback feedback) {
        if (actor == null || !actor.isEnabled()) throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue");
        String owner = actor.accountId().toString();
        if (key == null || !key.matches("[a-zA-Z0-9-]{16,80}")) invalid("A valid request key is required");
        if (feedback == null || feedback.type() == null || !Set.of("feedback", "problem", "question").contains(feedback.type())) invalid("Choose a feedback type");
        text(feedback.subject(), 160); text(feedback.message(), 5000);
        if (feedback.subject().contains("\r") || feedback.subject().contains("\n") || !email(feedback.replyTo())) invalid("Check the subject and reply email");
        List<Attachment> attachments = feedback.images() == null ? List.of() : feedback.images();
        if (attachments.size() > 3) invalid("Attach at most three PNG or JPEG images");
        List<Image> images = new ArrayList<>(); int total = 0;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((feedback.type()+"\0"+feedback.subject()+"\0"+feedback.message()+"\0"+feedback.replyTo()).getBytes(StandardCharsets.UTF_8));
            for (Attachment attachment : attachments) {
                if (attachment == null || attachment.data() == null || attachment.data().length() > 2_800_000) invalid("Each image must be at most 2 MB");
                byte[] raw = Base64.getDecoder().decode(attachment.data()); total += raw.length;
                if (raw.length > 2 * 1024 * 1024 || total > 5 * 1024 * 1024) invalid("Images exceed the 5 MB total limit");
                digest.update(raw);
                try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) invalid("Only PNG and JPEG images are supported");
                    var reader = readers.next();
                    try {
                        reader.setInput(input);
                        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                        if (!Set.of("png", "jpeg", "jpg").contains(format) || (long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000) invalid("Unsupported image format or dimensions");
                        var image = reader.read(0); var normalized = new ByteArrayOutputStream();
                        ImageIO.write(image, "png", normalized);
                        if (normalized.size() > 8 * 1024 * 1024) invalid("Image is too large after safe conversion");
                        images.add(new Image("screenshot-"+(images.size()+1)+".png", normalized.toByteArray()));
                    } finally { reader.dispose(); }
                }
            }
            String fingerprint = HexFormat.of().formatHex(digest.digest());
            JavaMailSender mail = sender.getIfAvailable();
            // DLV-969 (owner, 2026-11-04): name the missing setting, never an address.
            String missing = mail == null ? "spring.mail.host (no mail sender is configured)" : !email(from) ? "app.support.from" : !email(inbox) ? "app.support.inbox" : null;
            var reservation = receipts.reserve(owner,key,fingerprint);
            if (!reservation.dispatch()) return reservation.receipt();
            String reference = reservation.receipt().reference();
            Receipt receipt = reservation.receipt();
            // DLV-971: record the request for Team Reviews first; a store failure is logged and the email still goes.
            boolean recorded = record(actor, feedback, images, reference);
            if (missing != null) {
                if (recorded) {
                    Events.failed(Events.EVENT, "support.send", "EMAIL_NOT_SENT").addKeyValue("support.reference", reference).addKeyValue("setting", missing)
                            .log("Support request {} was recorded for Team Reviews but its email was not sent: {} is missing or not an email address", reference, missing);
                    return new Receipt(reference,"recorded");
                }
                // Neither recorded nor sent: free the request key so a retry starts over, and say so.
                receipts.release(owner,key);
                Events.failed(Events.EVENT, "support.send", "SUPPORT_UNAVAILABLE").addKeyValue("setting", missing)
                        .log("Support message refused with 503: {} is missing or not an email address, and the request could not be recorded for Team Reviews", missing);
                throw new AccountFailure(503,"SUPPORT_UNAVAILABLE","Support is not available right now. Your message was not recorded or sent; try again later");
            }
            try {
                var mime = mail.createMimeMessage(); var helper = new MimeMessageHelper(mime,!images.isEmpty(),"UTF-8");
                helper.setFrom(from); helper.setTo(inbox); helper.setReplyTo(feedback.replyTo());
                helper.setSubject("["+feedback.type()+"] "+feedback.subject());
                helper.setText("Reference: "+reference+"\n\n"+feedback.message(),false);
                for (Image image : images) helper.addAttachment(image.name(),new ByteArrayResource(image.bytes()),"image/png");
                mail.send(mime);
                receipts.accepted(owner,key);
                receipt = new Receipt(reference,"accepted");
            } catch (Exception unavailable) {
                // Keep an unconfirmed receipt; a retry must not send twice. DLV-969: say the email did not go.
                Events.failed(Events.EVENT, "support.send", "EMAIL_NOT_SENT").addKeyValue("support.reference", reference)
                        .addKeyValue("error.type", Events.types(unavailable))
                        .log("Support message {} was saved but its email was not sent ({}); the receipt stays unconfirmed", reference, Events.types(unavailable));
            }
            return receipt;
        } catch (AccountFailure failure) { throw failure; }
        catch (org.springframework.dao.DataAccessException unavailable) { throw unavailable; }
        catch (org.springframework.transaction.TransactionException unavailable) { throw unavailable; }
        catch (Exception invalid) {
            // Usually a damaged image; the types tell a damaged upload from a defect here.
            Events.refused(Events.EVENT, "support.send", "FEEDBACK_INVALID").addKeyValue("error.type", Events.types(invalid))
                    .log("Support message refused with 422: its fields or images could not be read ({})", Events.types(invalid));
            throw new AccountFailure(422,"FEEDBACK_INVALID","Check the message and PNG or JPEG attachments");
        }
    }
    /** True when the request is now an item in the review store; false when the store is unconfigured or failed (logged). */
    private boolean record(AccountPrincipal actor, Feedback feedback, List<Image> images, String reference) {
        try {
            var uploads = images.stream().map(image -> new PlatformReviewModels.Upload(image.name(),"image/png",image.bytes())).toList();
            reviews.recordRequest(actor, feedback.type(), feedback.subject(), feedback.message(), uploads, reference);
            return true;
        } catch (RuntimeException failure) {
            String code = failure instanceof AccountFailure refused ? refused.code() : null;
            Events.failed(Events.EVENT, "support.record", "REQUEST_NOT_RECORDED").addKeyValue("support.reference", reference)
                    .addKeyValue("error.type", Events.types(failure)).addKeyValue("error.code", code)
                    .log("Support request {} was not recorded for Team Reviews ({}{}); it is still sent by email", reference, Events.types(failure),
                            code == null ? "" : ", " + code);
            return false;
        }
    }
    private static void text(String value,int limit) { if (value == null || value.isBlank() || value.length() > limit) invalid("Message fields exceed their limits"); }
    private static boolean email(String value) { return value != null && value.length() <= 254 && value.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+"); }
    private static void invalid(String message) { throw new AccountFailure(422,"FEEDBACK_INVALID",message); }
}
