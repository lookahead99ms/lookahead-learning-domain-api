package com.lookahead.domain.review.platform;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.platform.PlatformReviewModels.*;

/**
 * DLV-928 platform reviews. Every review written on a day (UTC) is kept in one file,
 * reviews/{yyyy-MM-dd}.json; images are separate objects under review-images/{itemId}/.
 * Each change reads the day file, applies the change and writes it back only if nobody else
 * wrote it in between, so two reviewers saving at the same moment never overwrite each other.
 *
 * The owner is the account with the server's author capability. Reviewers come from the
 * app.platform-reviews.reviewers setting until account roles are managed in the admin pages.
 */
@Service
@Profile("accounts")
public class PlatformReviewService {
    static final int TITLE_LENGTH = 120, DETAILS_LENGTH = 5000, NOTE_LENGTH = 2000, PAGE_URL_LENGTH = 500;
    static final int IMAGES_PER_ITEM = 6, IMAGE_BYTES = 5 * 1024 * 1024, ITEM_IMAGE_BYTES = 20 * 1024 * 1024;
    static final int ITEMS_PER_REVIEWER_PER_DAY = 100, DAYS_PER_PAGE = 30, WRITE_ATTEMPTS = 8;
    private static final String DAYS = "reviews/", IMAGES = "review-images/";

    private final PlatformReviewObjects objects;
    private final LocalAuthorAccess author;
    private final Set<String> reviewers;
    private final ObjectMapper mapper;
    private final Clock clock;
    private com.lookahead.domain.access.StaffAccessService access;
    @Autowired
    public void configureStaffAccess(com.lookahead.domain.access.StaffAccessService access) { this.access=access; }
    private final java.util.concurrent.locks.ReentrantLock writes = new java.util.concurrent.locks.ReentrantLock();

    @Autowired
    public PlatformReviewService(PlatformReviewObjects objects, LocalAuthorAccess author, ObjectMapper mapper,
                                 @Value("${app.platform-reviews.reviewers:}") String reviewers) {
        this(objects, author, mapper, reviewers, Clock.systemUTC());
    }

    PlatformReviewService(PlatformReviewObjects objects, LocalAuthorAccess author, ObjectMapper mapper, String reviewers, Clock clock) {
        this.objects = objects; this.author = author; this.mapper = mapper; this.clock = clock;
        this.reviewers = Arrays.stream(reviewers.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    // ---- Reads

    public Viewer viewer(AccountPrincipal actor) {
        boolean owner = role(actor);
        return new Viewer(access == null ? (owner ? "owner" : "reviewer") : access.permissions(actor).feedbackRole(), reviewerId(actor.accountId()), displayName(actor));
    }

    /** Newest first, at most DAYS_PER_PAGE day files strictly before {@code before} (exclusive). */
    public Page list(AccountPrincipal actor, String before) {
        boolean owner = role(actor);
        LocalDate limit = before == null ? null : day(before);
        var days = days().stream().filter(day -> limit == null || day.isBefore(limit)).toList();
        var page = new ArrayList<Summary>();
        int read = 0;
        for (LocalDate day : days) {
            if (read == DAYS_PER_PAGE) return new Page(page, days.get(read - 1).toString());
            read++;
            var items = new ArrayList<>(load(day).items());
            Collections.reverse(items);
            for (StoredItem item : items) if (owner || mine(item, actor)) page.add(summary(item));
        }
        return new Page(page, null);
    }

    public Item item(AccountPrincipal actor, String id) {
        return view(visible(actor, id).item());
    }

    public Content attachment(AccountPrincipal actor, String id, String attachmentId) {
        var found = visible(actor, id);
        var attachment = found.item().attachments().stream().filter(a -> a.id().equals(attachmentId)).findFirst()
                .orElseThrow(PlatformReviewService::notFound);
        byte[] bytes = storage(() -> objects.read(attachment.path())).map(PlatformReviewObjects.Stored::bytes)
                .orElseThrow(PlatformReviewService::notFound);
        return new Content(attachment.contentType(), bytes);
    }

    // ---- Writes

    public Receipt create(AccountPrincipal actor, UUID key, Fields fields, List<Upload> uploads) {
        role(actor);
        if (key == null) throw invalid("Retry the request from the page.");
        Fields clean = fields(fields);
        var images = images(uploads, List.of());
        String hash = hash(clean, images);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var replay = findCreate(actor, key, today);
        if (replay.isPresent()) {
            if (!replay.get().createHash().equals(hash))
                throw new AccountFailure(409, "PLATFORM_REVIEW_IDEMPOTENCY_CONFLICT", "Reload the page before sending this item again.");
            return new Receipt(view(replay.get()), true);
        }
        String id = UUID.randomUUID().toString();
        var attachments = store(id, images);
        String now = clock.instant().toString();
        var item = new StoredItem(id, actor.accountId(), reviewerId(actor.accountId()), displayName(actor), clean.category(),
                clean.page(), clean.pageUrl(), clean.title(), clean.details(), clean.priority(), attachments, "new", "",
                now, now, key, hash, key, access == null ? (author.allowed(actor) ? "owner" : "reviewer") : access.permissions(actor).feedbackRole());
        try {
            change(today, items -> {
                var prior = items.stream().filter(i -> key.equals(i.createKey()) && mine(i, actor)).findFirst();
                if (prior.isPresent()) return new Change<>(items, prior.get(), false);
                if (items.stream().filter(i -> mine(i, actor)).count() >= ITEMS_PER_REVIEWER_PER_DAY)
                    throw invalid("You have added the most items allowed for one day. Add more tomorrow.");
                var next = new ArrayList<>(items);
                next.add(item);
                return new Change<>(next, item, true);
            });
        } catch (RuntimeException failure) {
            discard(attachments);
            throw failure;
        }
        return new Receipt(view(item), false);
    }

    public Item update(AccountPrincipal actor, String id, UUID key, Fields fields, List<String> keep, List<Upload> uploads) {
        role(actor);
        if (key == null) throw invalid("Retry the request from the page.");
        Fields clean = fields(fields);
        var found = own(actor, id);
        if (key.equals(found.item().lastWriteKey())) return view(found.item());
        editable(found.item());
        if (keep == null || new HashSet<>(keep).size() != keep.size()
                || !found.item().attachments().stream().map(StoredAttachment::id).collect(Collectors.toSet()).containsAll(keep))
            throw invalid("Reload the item; its images changed.");
        var kept = found.item().attachments().stream().filter(a -> keep.contains(a.id())).toList();
        var added = store(id, images(uploads, kept));
        var removed = new ArrayList<StoredAttachment>();
        StoredItem saved;
        try {
            saved = change(found.day(), items -> {
                var next = new ArrayList<>(items);
                int at = indexOf(next, id);
                StoredItem current = next.get(at);
                if (!mine(current, actor)) throw notFound();
                editable(current);
                if (!current.attachments().containsAll(kept)) throw invalid("Reload the item; its images changed.");
                var attachments = new ArrayList<>(kept);
                attachments.addAll(added);
                removed.clear();
                current.attachments().stream().filter(a -> !kept.contains(a)).forEach(removed::add);
                var updated = new StoredItem(id, current.reviewerAccountId(), current.reviewerId(), current.reviewerName(),
                        clean.category(), clean.page(), clean.pageUrl(), clean.title(), clean.details(), clean.priority(),
                        List.copyOf(attachments), current.status(), current.ownerNote(), current.createdAt(),
                        clock.instant().toString(), current.createKey(), current.createHash(), key, current.submitterRole());
                next.set(at, updated);
                return new Change<>(next, updated, true);
            });
        } catch (RuntimeException failure) {
            discard(added);
            throw failure;
        }
        discard(removed);
        return view(saved);
    }

    public void delete(AccountPrincipal actor, String id) {
        role(actor);
        var found = own(actor, id);
        editable(found.item());
        var removed = change(found.day(), items -> {
            var next = new ArrayList<>(items);
            StoredItem current = next.remove(indexOf(next, id));
            if (!mine(current, actor)) throw notFound();
            editable(current);
            return new Change<>(next, current, true);
        });
        discard(removed.attachments());
    }

    public Item triage(AccountPrincipal actor, String id, String status, String note) {
        if (!role(actor)) throw denied();
        if (status == null || !STATUSES.contains(status)) throw invalid("Choose a status.");
        String ownerNote = text(note == null ? "" : note, NOTE_LENGTH, true, "Keep the note within 2000 characters.").strip();
        var found = visible(actor, id);
        return view(change(found.day(), items -> {
            var next = new ArrayList<>(items);
            int at = indexOf(next, id);
            StoredItem c = next.get(at);
            var updated = new StoredItem(c.id(), c.reviewerAccountId(), c.reviewerId(), c.reviewerName(), c.category(), c.page(),
                    c.pageUrl(), c.title(), c.details(), c.priority(), c.attachments(), status, ownerNote, c.createdAt(),
                    clock.instant().toString(), c.createKey(), c.createHash(), c.lastWriteKey(), c.submitterRole());
            next.set(at, updated);
            return new Change<>(next, updated, true);
        }));
    }

    public record Receipt(Item item, boolean replayed) {}

    // ---- Access

    /** True for the owner, false for a reviewer; anyone else is denied. */
    private boolean role(AccountPrincipal actor) {
        if (actor == null || !actor.isEnabled()) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
        if (access != null) return access.permissions(actor).triageFeedback();
        if (author.allowed(actor)) return true;
        if (actor.getUsername() != null && reviewers.contains(actor.getUsername().toLowerCase(Locale.ROOT))) return false;
        throw denied();
    }

    private Located visible(AccountPrincipal actor, String id) {
        boolean owner = role(actor);
        var found = locate(id);
        if (!owner && !mine(found.item(), actor)) throw notFound();
        return found;
    }

    /** Only the account that wrote an item may edit or delete it, the owner included. */
    private Located own(AccountPrincipal actor, String id) {
        var found = locate(id);
        if (!mine(found.item(), actor)) throw notFound();
        return found;
    }

    private static boolean mine(StoredItem item, AccountPrincipal actor) {
        return item.reviewerAccountId().equals(actor.accountId());
    }

    private static void editable(StoredItem item) {
        if (!"new".equals(item.status()))
            throw new AccountFailure(409, "PLATFORM_REVIEW_LOCKED", "The owner has already reviewed this item, so it can no longer be changed.");
    }

    // ---- Day files

    private record Located(LocalDate day, StoredItem item) {}
    private record Change<T>(List<StoredItem> items, T result, boolean write) {}

    private Located locate(String id) {
        try { UUID.fromString(id); } catch (IllegalArgumentException | NullPointerException bad) { throw notFound(); }
        for (LocalDate day : days())
            for (StoredItem item : load(day).items())
                if (item.id().equals(id)) return new Located(day, item);
        throw notFound();
    }

    private Optional<StoredItem> findCreate(AccountPrincipal actor, UUID key, LocalDate today) {
        for (LocalDate day : List.of(today, today.minusDays(1)))
            for (StoredItem item : load(day).items())
                if (key.equals(item.createKey()) && mine(item, actor)) return Optional.of(item);
        return Optional.empty();
    }

    /**
     * Writes from this process take turns; the conditional write still guards against another
     * Domain instance writing the same day file (several tasks sharing one S3 bucket).
     */
    private <T> T change(LocalDate day, Function<List<StoredItem>, Change<T>> edit) {
        String key = DAYS + day + ".json";
        writes.lock();
        try {
            for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
                if (attempt > 0) pause(attempt);
                var stored = storage(() -> objects.read(key));
                List<StoredItem> items = stored.map(s -> parse(s.bytes()).items()).orElse(List.of());
                Change<T> result = edit.apply(items);
                if (!result.write()) return result.result();
                byte[] bytes = serialize(new DayFile(DAY_SCHEMA, day.toString(), List.copyOf(result.items())));
                if (storage(() -> objects.write(key, bytes, stored.map(PlatformReviewObjects.Stored::version).orElse(null))))
                    return result.result();
            }
        } finally {
            writes.unlock();
        }
        throw unavailable();
    }

    private static void pause(int attempt) {
        try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(10L, 40L * attempt)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
    }

    private DayFile load(LocalDate day) {
        return storage(() -> objects.read(DAYS + day + ".json")).map(s -> parse(s.bytes()))
                .orElse(new DayFile(DAY_SCHEMA, day.toString(), List.of()));
    }

    private List<LocalDate> days() {
        return storage(() -> objects.list(DAYS)).stream()
                .map(key -> key.substring(DAYS.length()))
                .filter(name -> name.matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                .map(name -> LocalDate.parse(name.substring(0, 10)))
                .sorted(Comparator.reverseOrder()).toList();
    }

    private DayFile parse(byte[] bytes) {
        try {
            var file = mapper.readValue(bytes, DayFile.class);
            if (!DAY_SCHEMA.equals(file.schema()) || file.items() == null) throw unavailable();
            // DLV-931: items saved before the rename read as "Blaze Catalog"; the next write of the day stores it.
            if (file.items().stream().noneMatch(i -> LEGACY_BLAZE_PAGE.equals(i.page()))) return file;
            return new DayFile(file.schema(), file.day(), file.items().stream().map(i -> !LEGACY_BLAZE_PAGE.equals(i.page()) ? i
                    : new StoredItem(i.id(), i.reviewerAccountId(), i.reviewerId(), i.reviewerName(), i.category(),
                            currentPage(i.page()), i.pageUrl(), i.title(), i.details(), i.priority(), i.attachments(), i.status(),
                            i.ownerNote(), i.createdAt(), i.updatedAt(), i.createKey(), i.createHash(), i.lastWriteKey(), i.submitterRole())).toList());
        } catch (JacksonException damaged) {
            throw unavailable();
        }
    }

    private byte[] serialize(DayFile file) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(file);
    }

    // ---- Images

    private record Prepared(String name, Image image) {}

    private List<Prepared> images(List<Upload> uploads, List<StoredAttachment> kept) {
        var list = uploads == null ? List.<Upload>of() : uploads;
        if (kept.size() + list.size() > IMAGES_PER_ITEM) throw invalid("Attach at most 6 images to one item.");
        long total = kept.stream().mapToLong(StoredAttachment::size).sum();
        var images = new ArrayList<Prepared>();
        for (Upload upload : list) {
            if (upload.bytes().length > IMAGE_BYTES) throw tooLarge("Each image must be 5 MB or smaller.");
            if (upload.bytes().length == 0) throw invalid("An image is empty.");
            Image image;
            try { image = PlatformReviewImages.clean(upload.declaredType(), upload.bytes()); }
            catch (IllegalArgumentException rejected) { throw invalid(rejected.getMessage()); }
            total += upload.bytes().length;
            images.add(new Prepared(name(upload.name()), image));
        }
        if (total > ITEM_IMAGE_BYTES) throw tooLarge("Images on one item must total 20 MB or less.");
        return images;
    }

    private List<StoredAttachment> store(String itemId, List<Prepared> images) {
        var stored = new ArrayList<StoredAttachment>();
        try {
            for (Prepared prepared : images) {
                Image image = prepared.image();
                String id = UUID.randomUUID().toString();
                String path = IMAGES + itemId + "/" + id + "." + image.extension();
                storage(() -> { objects.put(path, image.bytes()); return null; });
                stored.add(new StoredAttachment(id, prepared.name(), image.contentType(), image.bytes().length,
                        FileSystemPlatformReviewObjects.version(image.bytes()), path));
            }
            return List.copyOf(stored);
        } catch (RuntimeException failure) {
            discard(stored);
            throw failure;
        }
    }

    private void discard(List<StoredAttachment> attachments) {
        for (StoredAttachment attachment : attachments) {
            try { objects.delete(attachment.path()); }
            catch (RuntimeException ignored) { /* An orphaned image is unreachable without its record. */ }
        }
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

    private static int indexOf(List<StoredItem> items, String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) return i;
        throw notFound();
    }

    private static String submitterRole(StoredItem item) {
        return item.submitterRole() == null ? "legacy" : item.submitterRole();
    }
    private static Item view(StoredItem i) {
        return new Item(i.id(), i.reviewerId(), i.reviewerName(), i.category(), i.page(), i.pageUrl(), i.title(), i.details(),
                i.priority(), i.attachments().stream().map(a -> new Attachment(a.id(), a.name(), a.contentType(), a.size())).toList(),
                i.status(), i.ownerNote(), i.createdAt(), i.updatedAt(), submitterRole(i));
    }

    private static Summary summary(StoredItem i) {
        return new Summary(i.id(), i.reviewerId(), i.reviewerName(), i.category(), i.page(), i.title(), i.priority(), i.status(),
                i.attachments().size(), !i.ownerNote().isEmpty(), i.createdAt(), i.updatedAt(), submitterRole(i));
    }

    /** Opaque and stable per account; the account UUID is never sent to the browser. */
    private static String reviewerId(UUID accountId) {
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

    private static <T> T storage(java.util.function.Supplier<T> call) {
        try { return call.get(); }
        catch (UncheckedIOException failure) { throw unavailable(); }
    }

    static AccountFailure invalid(String message) { return new AccountFailure(422, "PLATFORM_REVIEW_INVALID", message); }
    static AccountFailure tooLarge(String message) { return new AccountFailure(413, "PLATFORM_REVIEW_TOO_LARGE", message); }
    private static AccountFailure notFound() { return new AccountFailure(404, "PLATFORM_REVIEW_NOT_FOUND", "This review item is not available."); }
    private static AccountFailure denied() { return new AccountFailure(403, "PLATFORM_REVIEW_ACCESS_DENIED", "Platform review access is unavailable for this account."); }
    private static AccountFailure unavailable() {
        return new AccountFailure(503, "PLATFORM_REVIEW_STORAGE_UNAVAILABLE", "Review storage is unavailable. Nothing was lost; try again.");
    }
}
