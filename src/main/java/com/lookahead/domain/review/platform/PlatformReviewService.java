package com.lookahead.domain.review.platform;

import com.lookahead.domain.Events;
import com.lookahead.domain.delivery.DeliveryPlanService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.platform.PlatformReviewModels.*;

/**
 * DLV-928 platform reviews. DLV-971 (owner, 2026-11-06): one JSON record per item in two runtime stores of the
 * assets bucket, reviews ({@code REV-000001}) and learner support requests ({@code SUP-000001}), attachments beside
 * each record. {@code GET /api/v1/platform-reviews} lists both stores merged, newest first, paged by day. A change
 * reads the record, applies the change and writes it back only if nobody else wrote it in between. Every status
 * change is one row of {@code review_status_events}. Promote adds a ticket to the delivery plan and links it to
 * the record; the ticket's status is read from the plan when an item is shown. Support requests with no update for
 * {@link #REQUEST_EXPIRY} are closed by the sweep; reviews never.
 *
 * The owner is the account with the server's author capability. Reviewers come from the
 * app.platform-reviews.reviewers setting until account roles are managed in the admin pages.
 */
@Service
@Profile("accounts")
public class PlatformReviewService {
    static final int TITLE_LENGTH = 120, DETAILS_LENGTH = 5000, NOTE_LENGTH = 2000, PAGE_URL_LENGTH = 500;
    static final int IMAGES_PER_ITEM = 6, IMAGE_BYTES = 5 * 1024 * 1024, ITEM_IMAGE_BYTES = 20 * 1024 * 1024;
    static final int ITEMS_PER_REVIEWER_PER_DAY = 100, DAYS_PER_PAGE = 30;
    public static final Duration REQUEST_EXPIRY = Duration.ofDays(7);
    public static final String AUTO_CLOSE_NOTE = "No update in 7 days; closed automatically. Reply or reopen to continue.";

    /** The two record stores: reviews and learner support requests. */
    public record Stores(RecordStore reviews, RecordStore requests) {
        public RecordStore of(String id) { return requests.owns(id) ? requests : reviews; }
    }

    private final Stores stores;
    private final LocalAuthorAccess author;
    private final Set<String> reviewers;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ReviewStatusHistory history;
    private com.lookahead.domain.access.StaffAccessService access;
    @Autowired
    public void configureStaffAccess(com.lookahead.domain.access.StaffAccessService access) { this.access=access; }
    private com.lookahead.domain.access.StaffAliases aliases;
    @Autowired(required = false)
    public void configureAliases(com.lookahead.domain.access.StaffAliases aliases) { this.aliases=aliases; }
    private DeliveryPlanService plans;
    @Autowired(required = false)
    public void configureDelivery(DeliveryPlanService plans) { this.plans = plans; }

    @Autowired
    public PlatformReviewService(Stores stores, LocalAuthorAccess author, ObjectMapper mapper,
                                 @Value("${app.platform-reviews.reviewers:}") String reviewers, ReviewStatusHistory history) {
        this(stores, author, mapper, reviewers, Clock.systemUTC(), history);
    }

    PlatformReviewService(Stores stores, LocalAuthorAccess author, ObjectMapper mapper, String reviewers, Clock clock, ReviewStatusHistory history) {
        this.stores = stores; this.author = author; this.mapper = mapper; this.clock = clock; this.history = history;
        this.reviewers = Arrays.stream(reviewers.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    // ---- Reads

    public Viewer viewer(AccountPrincipal actor) {
        boolean owner = role(actor);
        return new Viewer(access == null ? (owner ? "owner" : "reviewer") : access.permissions(actor).feedbackRole(), reviewerId(actor.accountId()), displayName(actor));
    }

    /** Both stores merged, newest first, at most DAYS_PER_PAGE days strictly before {@code before} (exclusive). */
    public Page list(AccountPrincipal actor, String before) {
        boolean owner = role(actor);
        LocalDate limit = before == null ? null : day(before);
        var reviewDays = stores.reviews().keysByDay();
        var requestDays = owner ? stores.requests().keysByDay() : Map.<String, List<String>>of();
        var days = new TreeSet<String>(Comparator.reverseOrder());
        days.addAll(reviewDays.keySet()); days.addAll(requestDays.keySet());
        var selected = days.stream().filter(day -> limit == null || LocalDate.parse(day).isBefore(limit)).toList();
        var page = new ArrayList<Summary>();
        var statuses = ticketStatuses();
        int read = 0;
        for (String day : selected) {
            if (read == DAYS_PER_PAGE) return new Page(page, selected.get(read - 1));
            read++;
            var records = new ArrayList<StoredRecord>();
            stores.reviews().readAll(reviewDays.getOrDefault(day, List.of())).forEach(found -> records.add(found.record()));
            stores.requests().readAll(requestDays.getOrDefault(day, List.of())).forEach(found -> records.add(found.record()));
            records.sort(Comparator.comparing(StoredRecord::createdAt).thenComparing(StoredRecord::id).reversed());
            for (StoredRecord record : records) if (owner || readable(record, actor)) page.add(summary(shown(actor, record), statuses));
        }
        return new Page(page, null);
    }

    public Item item(AccountPrincipal actor, String id) {
        return view(shown(actor, visible(actor, id).record()), ticketStatuses());
    }

    public Content attachment(AccountPrincipal actor, String id, String attachmentId) {
        var found = visible(actor, id);
        var attachment = found.record().attachments().stream().filter(a -> a.id().equals(attachmentId)).findFirst()
                .orElseThrow(PlatformReviewService::notFound);
        byte[] bytes = stores.of(id).readAttachment(attachment.path()).orElseThrow(PlatformReviewService::notFound);
        return new Content(attachment.contentType(), bytes);
    }

    // ---- Writes

    public Receipt create(AccountPrincipal actor, UUID key, Fields fields, List<Upload> uploads) {
        role(actor);
        // DLV-971 (owner, 2026-11-06): no review role today (a learner, a test account): nothing new, whatever was written before.
        if (access != null && "user".equals(access.permissions(actor).feedbackRole())) throw denied();
        if (key == null) throw invalid("Retry the request from the page.");
        Fields clean = fields(fields);
        var images = images(uploads, List.of());
        String hash = hash(clean, images);
        var recent = recent(stores.reviews());
        var replay = recent.stream().filter(r -> key.equals(r.createKey()) && mine(r, actor)).findFirst();
        if (replay.isPresent()) {
            if (!replay.get().createHash().equals(hash))
                throw new AccountFailure(409, "PLATFORM_REVIEW_IDEMPOTENCY_CONFLICT", "Reload the page before sending this item again.");
            Events.success(Events.EVENT, "platform-review.submit").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", replay.get().id())
                    .addKeyValue("repeated", true).log("Platform review item {} sent again with the same key: the saved item is returned", replay.get().id());
            return new Receipt(view(replay.get(), ticketStatuses()), true);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (recent.stream().filter(r -> mine(r, actor) && today.toString().equals(r.createdAt().substring(0, 10))).count() >= ITEMS_PER_REVIEWER_PER_DAY) {
            Events.limit("platform-review.submit", "PLATFORM_REVIEW_DAILY_LIMIT", "platform-review-items", ITEMS_PER_REVIEWER_PER_DAY, "1d UTC")
                    .addKeyValue("account.id", actor.accountId())
                    .log("Platform review limit hit for account {}: at most {} items a day (UTC)", actor.accountId(), ITEMS_PER_REVIEWER_PER_DAY);
            throw invalid("You have added the most items allowed for one day. Add more tomorrow.");
        }
        String id = stores.reviews().nextId();
        var attachments = store(stores.reviews(), id, images, 1);
        String now = clock.instant().toString();
        String role = access == null ? (author.allowed(actor) ? "owner" : "reviewer") : access.permissions(actor).feedbackRole();
        var record = new StoredRecord(RECORD_SCHEMA, id, KIND_REVIEW, null, "new", new Submitter(actor.accountId(), role, displayName(actor)),
                clean.category(), clean.title(), clean.details(), clean.page(), clean.pageUrl(), clean.priority(), attachments, null, null, "",
                now, now, null, null, key, hash, key);
        try { stores.reviews().create(record); }
        catch (RuntimeException failure) { stores.reviews().discard(attachments); throw failure; }
        Events.success(Events.EVENT, "platform-review.submit").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .addKeyValue("review.category", clean.category()).addKeyValue("review.page", clean.page()).addKeyValue("review.priority", clean.priority())
                .addKeyValue("count", attachments.size())
                .log("Platform review item {} submitted by account {}: {} on page {}, priority {}, {} image(s)", id, actor.accountId(),
                    clean.category(), clean.page(), clean.priority(), attachments.size());
        return new Receipt(view(record, Map.of()), false);
    }

    public Item update(AccountPrincipal actor, String id, UUID key, Fields fields, List<String> keep, List<Upload> uploads) {
        role(actor);
        if (key == null) throw invalid("Retry the request from the page.");
        Fields clean = fields(fields);
        var found = own(actor, id);
        if (key.equals(found.record().lastWriteKey())) return view(found.record(), ticketStatuses());
        editable(found.record());
        if (keep == null || new HashSet<>(keep).size() != keep.size()
                || !found.record().attachments().stream().map(StoredAttachment::id).collect(Collectors.toSet()).containsAll(keep))
            throw invalid("Reload the item; its images changed.");
        var kept = found.record().attachments().stream().filter(a -> keep.contains(a.id())).toList();
        RecordStore store = stores.of(id);
        var added = store(store, id, images(uploads, kept), nextIndex(found.record().attachments()));
        var removed = new ArrayList<StoredAttachment>();
        StoredRecord saved;
        try {
            saved = store.change(found.key(), current -> {
                if (!mine(current, actor)) throw notFound();
                editable(current);
                if (!current.attachments().containsAll(kept)) throw invalid("Reload the item; its images changed.");
                var attachments = new ArrayList<>(kept);
                attachments.addAll(added);
                removed.clear();
                current.attachments().stream().filter(a -> !kept.contains(a)).forEach(removed::add);
                return current.withFields(clean, List.copyOf(attachments), key, clock.instant().toString());
            }).orElseThrow(PlatformReviewService::notFound);
        } catch (RuntimeException failure) {
            store.discard(added);
            throw failure;
        }
        store.discard(removed);
        Events.success(Events.EVENT, "platform-review.update").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .addKeyValue("count", saved.attachments().size())
                .log("Platform review item {} updated by account {}: {} image(s) kept, {} added, {} removed", id, actor.accountId(), kept.size(), added.size(), removed.size());
        return view(saved, ticketStatuses());
    }

    public void delete(AccountPrincipal actor, String id) {
        role(actor);
        boolean owner = isOwner(actor);
        var found = locate(id);
        deletable(found.record(), actor, owner);
        RecordStore store = stores.of(id);
        store.delete(found.key());
        store.discard(found.record().attachments());
        Events.success(Events.EVENT, "platform-review.delete").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .log("Platform review item {} deleted by account {} with its {} image(s)", id, actor.accountId(), found.record().attachments().size());
    }

    /**
     * DLV-971 (owner, 2026-11-06): the owner sets the status of any item; an admin only of a learner's support request.
     * {@code closed} is set by hand or by the sweep; closed to any status (a reopen) follows the same rights.
     */
    public Item triage(AccountPrincipal actor, String id, String status, String note) {
        if (!role(actor)) throw denied();
        boolean owner = isOwner(actor);
        if (status == null || !STATUSES.contains(status)) throw invalid("Choose a status.");
        String ownerNote = text(note == null ? "" : note, NOTE_LENGTH, true, "Keep the note within 2000 characters.").strip();
        var found = visible(actor, id);
        if (!owner && !found.record().request()) throw denied();
        String[] before = {null};
        var saved = stores.of(id).change(found.key(), current -> {
            if (!owner && !current.request()) throw denied();
            before[0] = current.status();
            return current.withStatus(status, ownerNote, clock.instant().toString(), owner ? "owner" : "admin");
        }).orElseThrow(PlatformReviewService::notFound);
        if (!status.equals(before[0])) history.record(id, saved.kind(), actor.accountId(), before[0], status, !ownerNote.isEmpty());
        Events.success(Events.EVENT, "platform-review.status").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .addKeyValue("actor.role", owner ? "owner" : "admin").addKeyValue("review.kind", saved.kind())
                .addKeyValue("review.status.before", before[0]).addKeyValue("review.status", status)
                .log("Platform review item {} status changed by {}: {} -> {}", id, owner ? "the owner" : "an admin", before[0], status);
        return view(shown(actor, saved), ticketStatuses());
    }

    /**
     * Promote: a ticket on the delivery plan (title and summary from the record, the type from its kind, the priority
     * mapped, the workflow's first status, {@code origin {id, kind}}) and {@code ticketId} on the record. The owner
     * promotes any record, an admin a learner's request only; a record already promoted answers 409.
     */
    public Promoted promote(AccountPrincipal actor, String id) {
        if (!role(actor)) throw denied();
        boolean owner = isOwner(actor);
        var found = visible(actor, id);
        StoredRecord record = found.record();
        if (!owner && !record.request()) throw denied();
        if (record.ticketId() != null) throw alreadyPromoted(record.ticketId());
        if (plans == null) throw new AccountFailure(503, "DELIVERY_STORAGE_UNAVAILABLE", "The delivery plan is not configured.");
        var created = plans.promote(actor.accountId(), record.title(), record.details(), ticketType(record), record.priority(), record.id(), record.kind());
        String ticketId = created.item().path("id").asString();
        try {
            stores.of(id).change(found.key(), current -> {
                if (current.ticketId() != null) throw alreadyPromoted(current.ticketId());
                return current.withTicket(ticketId, clock.instant().toString());
            }).orElseThrow(PlatformReviewService::notFound);
        } catch (RuntimeException failure) {
            Events.failed(Events.EVENT, "platform-review.promote", failure instanceof AccountFailure refused ? refused.code() : "RECORD_NOT_LINKED")
                    .addKeyValue("target.id", id).addKeyValue("ticket.id", ticketId)
                    .log("Record {} was not linked to its new ticket {} ({}); the ticket stays on the plan", id, ticketId, Events.types(failure));
            throw failure;
        }
        Events.success(Events.EVENT, "platform-review.promote").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .addKeyValue("ticket.id", ticketId).addKeyValue("review.kind", record.kind()).addKeyValue("plan.version", created.version())
                .log("Platform review item {} promoted by {} to delivery ticket {} (plan version {})", id, owner ? "the owner" : "an admin", ticketId, created.version());
        return new Promoted(ticketId, created.item());
    }

    /** The plan's ticket type for a record: a problem is a bug, a question a task, the rest a story. */
    static String ticketType(StoredRecord record) {
        if (record.request()) return switch (record.requestType() == null ? "" : record.requestType()) { case "problem" -> "bug"; case "question" -> "task"; default -> "story"; };
        return switch (record.category() == null ? "" : record.category()) { case "problem" -> "bug"; case "content" -> "task"; default -> "story"; };
    }

    /**
     * DLV-971 (owner, 2026-11-06): a learner's support request (Report a problem, Share feedback, Ask a question) is
     * recorded in the support-requests store so the owner and admins see it under Team Reviews with its kind. The
     * learner needs no review role. The record is keyed by the support receipt {@code reference}: the same request is
     * never recorded twice. The review category is "problem" for a problem and "idea" for feedback or a question;
     * {@code requestType} carries the form's own type. The reply email is not stored; the receipt row and the email carry it.
     */
    public Item recordRequest(AccountPrincipal actor, String type, String subject, String message, List<Upload> uploads, String reference) {
        if (actor == null || !actor.isEnabled()) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
        if (type == null || !REQUEST_TYPES.contains(type) || reference == null || reference.isBlank()
                || subject == null || subject.isBlank() || message == null || message.isBlank())
            throw invalid("The request is incomplete.");
        var images = images(uploads, List.of());
        var replay = recent(stores.requests()).stream().filter(r -> reference.equals(r.reference())).findFirst();
        if (replay.isPresent()) return view(replay.get(), Map.of());
        String id = stores.requests().nextId();
        var attachments = store(stores.requests(), id, images, 1);
        String now = clock.instant().toString();
        String role = access == null ? (author.allowed(actor) ? "owner" : "user") : access.permissions(actor).feedbackRole();
        var record = new StoredRecord(RECORD_SCHEMA, id, KIND_REQUEST, type, "new", new Submitter(actor.accountId(), role, displayName(actor)),
                "problem".equals(type) ? "problem" : "idea", subject.strip(), message, "Support", null, "medium", attachments, reference, null, "",
                now, now, null, null, null, null, null);
        try { stores.requests().create(record); }
        catch (RuntimeException failure) { stores.requests().discard(attachments); throw failure; }
        Events.success(Events.EVENT, "platform-review.request").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .addKeyValue("support.reference", reference).addKeyValue("request.type", type).addKeyValue("count", attachments.size())
                .log("Support request {} recorded as Team Reviews item {} for account {}: {} with {} image(s)",
                        reference, id, actor.accountId(), type, attachments.size());
        return view(record, Map.of());
    }

    /**
     * The sweep: support requests still {@code new} or {@code accepted} with no update for {@link #REQUEST_EXPIRY} are
     * closed with {@link #AUTO_CLOSE_NOTE} by "system"; reviews are never swept. One line with the count.
     * @return the number of requests closed
     */
    public int closeStaleRequests() {
        Instant cutoff = clock.instant().minus(REQUEST_EXPIRY);
        int closed = 0;
        for (var found : stores.requests().all()) {
            StoredRecord record = found.record();
            if (!Set.of("new", "accepted").contains(record.status()) || !Instant.parse(record.updatedAt()).isBefore(cutoff)) continue;
            String[] before = {null};
            var saved = stores.requests().change(found.key(), current -> {
                if (!Set.of("new", "accepted").contains(current.status()) || !Instant.parse(current.updatedAt()).isBefore(cutoff)) return null;
                before[0] = current.status();
                return current.withStatus("closed", AUTO_CLOSE_NOTE, clock.instant().toString(), "system");
            });
            if (saved.isEmpty() || before[0] == null) continue;
            history.record(record.id(), KIND_REQUEST, null, before[0], "closed", true);
            Events.success(Events.EVENT, "platform-review.auto-close").addKeyValue("target.id", record.id()).addKeyValue("review.status.before", before[0])
                    .log("Support request {} closed automatically: no update in {} days", record.id(), REQUEST_EXPIRY.toDays());
            closed++;
        }
        Events.success(Events.EVENT, "platform-review.sweep").addKeyValue("count", closed)
                .log("Support request sweep closed {} request(s) with no update in {} days", closed, REQUEST_EXPIRY.toDays());
        return closed;
    }

    public record Receipt(Item item, boolean replayed) {}

    // ---- Access

    /** True for the owner or an admin (they read every item), false for a reviewer; anyone else is denied. */
    private boolean role(AccountPrincipal actor) {
        if (actor == null || !actor.isEnabled()) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
        if (access != null) return access.permissions(actor).triageFeedback();
        if (author.allowed(actor)) return true;
        if (actor.getUsername() != null && reviewers.contains(actor.getUsername().toLowerCase(Locale.ROOT))) return false;
        throw denied();
    }

    /** DLV-971: the owner alone may run the sweeps now: 401 without a sign-in, 403 for everyone else, admins included. */
    public void requireOwner(AccountPrincipal actor) {
        if (actor == null || !actor.isEnabled()) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
        if (!isOwner(actor)) throw denied();
    }

    /** The owner alone, never an admin: {@code manageAccess} with staff access, the author capability without it. */
    private boolean isOwner(AccountPrincipal actor) {
        return access != null ? access.permissions(actor).manageAccess() : author.allowed(actor);
    }

    private RecordStore.Located visible(AccountPrincipal actor, String id) {
        boolean owner = role(actor);
        var found = locate(id);
        if (!owner && !readable(found.record(), actor)) throw notFound();
        return found;
    }

    /** Only the account that wrote a review may edit it, the owner included; a request is never edited here. */
    private RecordStore.Located own(AccountPrincipal actor, String id) {
        var found = locate(id);
        if (!readable(found.record(), actor)) throw notFound();
        return found;
    }

    /** DLV-971: the owner removes a learner's request; a review is removed by its writer while it is New. */
    private static void deletable(StoredRecord record, AccountPrincipal actor, boolean owner) {
        if (record.request()) { if (!owner) throw notFound(); return; }
        if (!mine(record, actor)) throw notFound();
        editable(record);
    }

    private static boolean mine(StoredRecord record, AccountPrincipal actor) {
        return record.accountId().equals(actor.accountId());
    }

    /** A reviewer reads the reviews they wrote; requests are shown to the owner and admins only. */
    private static boolean readable(StoredRecord record, AccountPrincipal actor) {
        return !record.request() && mine(record, actor);
    }

    private static void editable(StoredRecord record) {
        if (!"new".equals(record.status()))
            throw new AccountFailure(409, "PLATFORM_REVIEW_LOCKED", "The owner has already reviewed this item, so it can no longer be changed.");
    }

    // ---- Records

    private RecordStore.Located locate(String id) {
        if (id == null || !(stores.reviews().owns(id) || stores.requests().owns(id))) throw notFound();
        return stores.of(id).find(id).orElseThrow(PlatformReviewService::notFound);
    }

    /** The records of today and yesterday (UTC): the window for a repeated create or request. */
    private List<StoredRecord> recent(RecordStore store) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var byDay = store.keysByDay();
        var keys = new ArrayList<String>();
        for (LocalDate day : List.of(today, today.minusDays(1))) keys.addAll(byDay.getOrDefault(day.toString(), List.of()));
        return store.readAll(keys).stream().map(RecordStore.Located::record).toList();
    }

    private Map<String, String> ticketStatuses() { return plans == null ? Map.of() : plans.ticketStatuses(); }

    // ---- Images

    private record Prepared(String name, Image image) {}

    private List<Prepared> images(List<Upload> uploads, List<StoredAttachment> kept) {
        var list = uploads == null ? List.<Upload>of() : uploads;
        if (kept.size() + list.size() > IMAGES_PER_ITEM) throw imageLimit("platform-review-images", IMAGES_PER_ITEM, invalid("Attach at most 6 images to one item."));
        long total = kept.stream().mapToLong(StoredAttachment::size).sum();
        var images = new ArrayList<Prepared>();
        for (Upload upload : list) {
            if (upload.bytes().length > IMAGE_BYTES) throw imageLimit("platform-review-image-bytes", IMAGE_BYTES, tooLarge("Each image must be 5 MB or smaller."));
            if (upload.bytes().length == 0) throw invalid("An image is empty.");
            Image image;
            try { image = PlatformReviewImages.clean(upload.declaredType(), upload.bytes()); }
            catch (IllegalArgumentException rejected) { throw invalid(rejected.getMessage()); }
            total += upload.bytes().length;
            images.add(new Prepared(name(upload.name()), image));
        }
        if (total > ITEM_IMAGE_BYTES) throw imageLimit("platform-review-item-image-bytes", ITEM_IMAGE_BYTES, tooLarge("Images on one item must total 20 MB or less."));
        return images;
    }

    /** Stores the images as attachments/<ID>/NN-name.ext, numbering from {@code first}. */
    private static List<StoredAttachment> store(RecordStore store, String itemId, List<Prepared> images, int first) {
        var stored = new ArrayList<StoredAttachment>();
        try {
            int index = first;
            for (Prepared prepared : images) {
                Image image = prepared.image();
                String id = UUID.randomUUID().toString();
                String path = RecordStore.attachmentKey(itemId, index++, prepared.name(), image.extension());
                store.putAttachment(path, image.bytes());
                stored.add(new StoredAttachment(id, prepared.name(), image.contentType(), image.bytes().length,
                        FileSystemPlatformReviewObjects.version(image.bytes()), path));
            }
            return List.copyOf(stored);
        } catch (RuntimeException failure) {
            store.discard(stored);
            throw failure;
        }
    }

    /** One past the highest NN among the record's attachment keys. */
    static int nextIndex(List<StoredAttachment> attachments) {
        int highest = 0;
        for (StoredAttachment attachment : attachments) {
            var match = java.util.regex.Pattern.compile("attachments/[^/]+/(\\d{2,})-").matcher(attachment.path());
            if (match.lookingAt()) highest = Math.max(highest, Integer.parseInt(match.group(1)));
        }
        return highest + 1;
    }

    private static String name(String name) {
        String clean = name == null ? "" : name.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (clean.isEmpty()) clean = "image";
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }

    // ---- Validation and views

    private static Fields fields(Fields fields) {
        if (fields == null) throw invalid("The item is incomplete.");
        if (fields.category() == null || !CATEGORIES.contains(fields.category())) throw invalid("Choose a type.");
        String page = currentPage(fields.page());
        if (page == null || !PAGES.contains(page)) throw invalid("Choose a page.");
        if (fields.priority() == null || !PRIORITIES.contains(fields.priority())) throw invalid("Choose how important this is.");
        String title = text(fields.title(), TITLE_LENGTH, false, "Keep the title within 120 characters.").strip();
        if (title.isEmpty()) throw invalid("Add a short title.");
        String details = text(fields.details(), DETAILS_LENGTH, true, "Keep the description within 5000 characters.");
        if (details.isBlank()) throw invalid("Describe what you saw and what should change.");
        String pageUrl = text(fields.pageUrl() == null ? "" : fields.pageUrl(), PAGE_URL_LENGTH, false,
                "The page address is too long.").strip();
        if (!pageUrl.isEmpty() && (!pageUrl.matches("/(?!/)\\S*") || pageUrl.indexOf('\\') >= 0))
            throw invalid("The page address must start with / and be on this site, for example /learn/core-java.");
        return new Fields(fields.category(), page, pageUrl, title, details, fields.priority());
    }

    private static String text(String value, int max, boolean multiline, String tooLong) {
        if (value == null) throw invalid("The item is incomplete.");
        if (value.length() > max) throw invalid(tooLong);
        if (value.codePoints().anyMatch(c -> (Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\t' || c == '\r')))
                || (c >= 0xd800 && c <= 0xdfff)))
            throw invalid("Remove unsupported characters from the text.");
        return value;
    }

    private String hash(Fields fields, List<Prepared> images) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(mapper.writeValueAsBytes(fields));
            for (Prepared image : images) digest.update(image.image().bytes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String submitterRole(StoredRecord record) {
        return record.submitter().role() == null ? "legacy" : record.submitter().role();
    }
    /**
     * DLV-969 (owner decision 2026-11-03): only the owner sees who wrote another person's item. An admin sees the
     * writer's role label ("Co-author 2"); everyone sees their own name on their own items.
     */
    private StoredRecord shown(AccountPrincipal viewer, StoredRecord record) {
        if (aliases == null || mine(record, viewer) || aliases.seesIdentities(viewer)) return record;
        return record.withSubmitterName(aliases.label(record.accountId()));
    }

    private static Item view(StoredRecord r, Map<String, String> statuses) {
        return new Item(r.id(), reviewerId(r.accountId()), r.submitter().displayName(), r.category(), r.page(), r.pageUrl(), r.title(), r.details(),
                r.priority(), r.attachments().stream().map(a -> new Attachment(a.id(), a.name(), a.contentType(), a.size())).toList(),
                r.status(), r.ownerNote(), r.createdAt(), r.updatedAt(), submitterRole(r), r.kind(), r.requestType(), r.reference(),
                r.ticketId(), ticketStatus(r, statuses), r.closedAt(), r.closedBy());
    }

    private static Summary summary(StoredRecord r, Map<String, String> statuses) {
        return new Summary(r.id(), reviewerId(r.accountId()), r.submitter().displayName(), r.category(), r.page(), r.title(), r.priority(), r.status(),
                r.attachments().size(), !r.ownerNote().isEmpty(), r.createdAt(), r.updatedAt(), submitterRole(r), r.kind(), r.requestType(), r.reference(),
                r.ticketId(), ticketStatus(r, statuses));
    }

    private static String ticketStatus(StoredRecord r, Map<String, String> statuses) {
        return r.ticketId() == null ? null : statuses.get(r.ticketId());
    }

    /** Opaque and stable per account; the account UUID is never sent to the browser. */
    static String reviewerId(UUID accountId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("platform-review-reviewer:" + accountId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String displayName(AccountPrincipal actor) {
        String name = actor.displayName();
        return name == null || name.isBlank() ? "Reviewer" : name.strip();
    }

    private static LocalDate day(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException bad) { throw invalid("Invalid date."); }
    }

    /** An image count or size limit hit: one {@code lookahead.limit} line naming the limit, then the caller's refusal. */
    static AccountFailure imageLimit(String name, long max, AccountFailure refusal) {
        Events.limit("platform-review.images", refusal.code(), name, max, "per item").addKeyValue("http.status", refusal.status())
                .log("Platform review image limit hit: {} (at most {}); refused with {}", name, max, refusal.status());
        return refusal;
    }

    static AccountFailure invalid(String message) { return new AccountFailure(422, "PLATFORM_REVIEW_INVALID", message); }
    static AccountFailure tooLarge(String message) { return new AccountFailure(413, "PLATFORM_REVIEW_TOO_LARGE", message); }
    private static AccountFailure notFound() { return new AccountFailure(404, "PLATFORM_REVIEW_NOT_FOUND", "This review item is not available."); }
    private static AccountFailure denied() { return new AccountFailure(403, "PLATFORM_REVIEW_ACCESS_DENIED", "Platform review access is unavailable for this account."); }
    private static AccountFailure alreadyPromoted(String ticketId) {
        return new AccountFailure(409, "PLATFORM_REVIEW_ALREADY_PROMOTED", "This item is already on the delivery plan as " + ticketId + ".");
    }
}
