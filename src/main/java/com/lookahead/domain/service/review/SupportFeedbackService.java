package com.lookahead.domain.service.review;

import com.lookahead.domain.dto.SupportFeedback;
import com.lookahead.domain.dto.SupportFeedbackAttachment;
import com.lookahead.domain.exception.StorageFailure;
import com.lookahead.domain.model.SupportFeedbackModels.*;
import com.lookahead.domain.client.SupportEmailClient;
import com.lookahead.domain.client.SupportEmailClient.Image;
import com.lookahead.domain.db.store.review.SupportReceipt;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.PlatformReviewModels;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.util.SupportFeedbackRules;
import com.lookahead.domain.validator.SupportFeedbackValidator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.transaction.TransactionException;

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
    private final SupportEmailClient email;
    private final SupportReceipt receipts;
    private final PlatformReviewService reviews;
    private final SupportFeedbackValidator feedbackValidator;
    public SupportFeedbackService(SupportEmailClient email, SupportReceipt receipts, PlatformReviewService reviews, SupportFeedbackValidator feedbackValidator) {
        this.email = email; this.receipts = receipts; this.reviews = reviews; this.feedbackValidator = feedbackValidator;
    }
    public Receipt submit(AccountPrincipal actor, String key, SupportFeedback feedback) {
        AccountPrincipal.requireSignedIn(actor, "Sign in to continue");
        String owner = actor.accountId().toString();
        List<SupportFeedbackAttachment> attachments = feedbackValidator.fields(key, feedback);
        try {
            // DLV-1016: the image checks are SupportFeedbackValidator's. The fingerprint is taken from the same bytes in
            // the same order as before (the text fields, then each image as it was decoded), once every image has passed.
            var checked = feedbackValidator.images(attachments);
            List<Image> images = checked.images();
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((feedback.type()+"\0"+feedback.subject()+"\0"+feedback.message()+"\0"+feedback.replyTo()).getBytes(StandardCharsets.UTF_8));
            for (byte[] raw : checked.decoded()) digest.update(raw);
            String fingerprint = HexFormat.of().formatHex(digest.digest());
            String missing = email.missing();
            var reservation = receipts.reserve(owner,key,fingerprint,SupportFeedbackRules.PER_WINDOW,SupportFeedbackRules.WINDOW);
            // DLV-1016: the store answers that nothing was reserved; the two refusals are made here, right after its call.
            if (reservation.refused() != null) throw switch (reservation.refused()) {
                case KEY_REUSED -> SupportFeedbackRules.keyReused();
                case QUOTA_REACHED -> SupportFeedbackRules.rateLimited();
            };
            if (!reservation.dispatch()) return reservation.receipt();
            String reference = reservation.receipt().reference();
            Receipt receipt = reservation.receipt();
            // DLV-971: record the request for Team Reviews first; a store failure is logged and the email still goes.
            boolean recorded = record(actor, feedback, images, reference);
            if (missing != null) {
                if (recorded) {
                    EventLog.failed(EventLog.EVENT, "support.send", "EMAIL_NOT_SENT").addKeyValue("support.reference", reference).addKeyValue("setting", missing)
                            .log("Support request {} was recorded for Team Reviews but its email was not sent: {} is missing or not an email address", reference, missing);
                    return new Receipt(reference,"recorded");
                }
                // Neither recorded nor sent: free the request key so a retry starts over, and say so.
                receipts.release(owner,key);
                EventLog.failed(EventLog.EVENT, "support.send", "SUPPORT_UNAVAILABLE").addKeyValue("setting", missing)
                        .log("Support message refused with 503: {} is missing or not an email address, and the request could not be recorded for Team Reviews", missing);
                throw new AccountFailure(503,"SUPPORT_UNAVAILABLE","Support is not available right now. Your message was not recorded or sent; try again later");
            }
            try {
                email.send(reference, feedback.type(), feedback.subject(), feedback.message(), feedback.replyTo(), images);
                receipts.accepted(owner,key);
                receipt = new Receipt(reference,"accepted");
            } catch (Exception unavailable) {
                // Keep an unconfirmed receipt; a retry must not send twice. DLV-969: say the email did not go.
                EventLog.failed(EventLog.EVENT, "support.send", "EMAIL_NOT_SENT").addKeyValue("support.reference", reference)
                        .addKeyValue("error.type", EventLog.types(unavailable))
                        .log("Support message {} was saved but its email was not sent ({}); the receipt stays unconfirmed", reference, EventLog.types(unavailable));
            }
            return receipt;
        } catch (AccountFailure failure) { throw failure; }
        catch (TransactionException unavailable) { throw unavailable; }
        // DLV-970: the store's own outcomes (one type, StorageFailure) are not a damaged request. An outage or an unanswered read is the
        // 503 ACCOUNT_STORAGE_UNAVAILABLE of the handler (OwnerReadUnavailable is one of them); a lost owner fence after the store's retries is its 409.
        catch (StorageFailure store) { throw store; }
        catch (Exception invalid) {
            // Usually a damaged image; the types tell a damaged upload from a defect here.
            EventLog.refused(EventLog.EVENT, "support.send", "FEEDBACK_INVALID").addKeyValue("error.type", EventLog.types(invalid))
                    .log("Support message refused with 422: its fields or images could not be read ({})", EventLog.types(invalid));
            throw new AccountFailure(422,"FEEDBACK_INVALID","Check the message and PNG or JPEG attachments");
        }
    }
    /** True when the request is now an item in the review store; false when the store is unconfigured or failed (logged). */
    private boolean record(AccountPrincipal actor, SupportFeedback feedback, List<Image> images, String reference) {
        try {
            var uploads = images.stream().map(image -> new PlatformReviewModels.Upload(image.name(),"image/png",image.bytes())).toList();
            reviews.recordRequest(actor, feedback.type(), feedback.subject(), feedback.message(), uploads, reference);
            return true;
        } catch (RuntimeException failure) {
            String code = failure instanceof AccountFailure refused ? refused.code() : null;
            EventLog.failed(EventLog.EVENT, "support.record", "REQUEST_NOT_RECORDED").addKeyValue("support.reference", reference)
                    .addKeyValue("error.type", EventLog.types(failure)).addKeyValue("error.code", code)
                    .log("Support request {} was not recorded for Team Reviews ({}{}); it is still sent by email", reference, EventLog.types(failure),
                            code == null ? "" : ", " + code);
            return false;
        }
    }
}
