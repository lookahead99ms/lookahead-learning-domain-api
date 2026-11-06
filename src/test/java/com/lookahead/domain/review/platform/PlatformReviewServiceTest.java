package com.lookahead.domain.review.platform;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static com.lookahead.domain.review.platform.PlatformReviewModels.*;
import static com.lookahead.domain.review.platform.PlatformReviewTestImages.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformReviewServiceTest {
    @TempDir Path root;
    final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "author@lookahead.test", "Owner", true);
    final AccountPrincipal first = new AccountPrincipal(UUID.randomUUID(), "first@example.test", "First Reviewer", true);
    final AccountPrincipal second = new AccountPrincipal(UUID.randomUUID(), "Second@Example.test", "Second Reviewer", true);
    final AccountPrincipal learner = new AccountPrincipal(UUID.randomUUID(), "learner@example.test", "Learner", true);
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-12T09:00:00Z"));
    final ReviewStatusHistory.InMemory history = new ReviewStatusHistory.InMemory();
    PlatformReviewService reviews;

    @BeforeEach void setup() {
        var author = mock(LocalAuthorAccess.class);
        when(author.allowed(owner)).thenReturn(true);
        reviews = PlatformReviewFixtures.service(root, author, JsonMapper.builder().build(), "first@example.test, second@example.test", clock, history);
    }

    @Test void reviewersSeeOnlyTheirOwnItemsAndTheOwnerSeesEveryone() {
        var mine = create(first, "Search misses Kafka", png());
        var theirs = create(second, "Plan dates overlap");
        var owners = create(owner, "Owner note to self");

        assertThat(reviews.viewer(first).role()).isEqualTo("reviewer");
        assertThat(reviews.viewer(owner).role()).isEqualTo("owner");
        assertThat(reviews.list(first, null).items()).extracting(Summary::id).containsExactly(mine.id());
        assertThat(reviews.list(owner, null).items()).extracting(Summary::id).containsExactly(owners.id(), theirs.id(), mine.id());

        String image = mine.attachments().getFirst().id();
        assertFailure(() -> reviews.item(second, mine.id()), 404);
        assertFailure(() -> reviews.attachment(second, mine.id(), image), 404);
        assertFailure(() -> reviews.update(second, mine.id(), UUID.randomUUID(), fields("Taken over"), List.of(), List.of()), 404);
        assertFailure(() -> reviews.delete(second, mine.id()), 404);
        assertThat(reviews.attachment(owner, mine.id(), image).contentType()).isEqualTo("image/png");
        assertThat(reviews.item(first, mine.id()).details()).isEqualTo("Steps to reproduce");
    }

    @Test void currentStaffPermissionsControlTriageAndSubmissionRoleIsHistorical() {
        var access = mock(com.lookahead.domain.access.StaffAccessService.class);
        reviews.configureStaffAccess(access);
        when(access.permissions(learner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("tester")));
        var item = create(learner, "Tester finding");
        assertThat(item.submitterRole()).isEqualTo("tester");
        when(access.permissions(learner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of()));
        assertThat(reviews.viewer(learner).role()).isEqualTo("user");
        // DLV-971: with no review role today the account keeps its earlier items but cannot add new ones.
        assertFailure(() -> reviews.create(learner, UUID.randomUUID(), fields("Nothing new"), List.of()), 403);
        assertThat(reviews.update(learner, item.id(), UUID.randomUUID(), fields("Updated finding"), List.of(), List.of()).submitterRole()).isEqualTo("tester");
        when(access.permissions(first)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("reviewer")));
        assertFailure(() -> reviews.item(first, item.id()), 404);
        assertFailure(() -> reviews.triage(first, item.id(), "accepted", ""), 403);
        when(access.permissions(second)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("admin")));
        assertThat(reviews.list(second, null).items()).extracting(Summary::id).containsExactly(item.id());
        // DLV-971 (owner, 2026-11-06): an admin reads every item but sets the status of a learner's request only.
        assertFailure(() -> reviews.triage(second, item.id(), "accepted", "Investigating"), 403);
        var request = reviews.recordRequest(learner, "question", "How do plans renew?", "Asked on the Support page", List.of(), UUID.randomUUID().toString());
        assertThat(request.submitterRole()).isEqualTo("user");
        assertThat(reviews.triage(second, request.id(), "done", "Answered by email").status()).isEqualTo("done");
        when(access.permissions(owner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(true, Set.of()));
        assertThat(reviews.triage(owner, item.id(), "accepted", "Investigating").submitterRole()).isEqualTo("tester");
        assertThat(reviews.triage(owner, request.id(), "declined", "").status()).isEqualTo("declined");
    }

    /**
     * DLV-971 (owner, 2026-11-06): a learner's support request is recorded next to the reviews with its kind, type and
     * receipt reference; the owner and admins see it, a reviewer never does, and the same reference is recorded once.
     */
    @Test void aSupportRequestIsRecordedWithItsKindForTheOwnerAndAdmins() throws Exception {
        var access = mock(com.lookahead.domain.access.StaffAccessService.class);
        reviews.configureStaffAccess(access);
        when(access.permissions(learner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of()));
        when(access.permissions(first)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("reviewer")));
        when(access.permissions(second)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("admin")));
        when(access.permissions(owner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(true, Set.of()));
        String reference = UUID.randomUUID().toString();
        Item request;
        try (var log = com.lookahead.domain.LogCapture.start()) {
            request = reviews.recordRequest(learner, "problem", "  Search page hangs  ", "Private steps", List.of(new Upload("screenshot-1.png", "image/png", png())), reference);
            var line = log.only("platform-review.request");
            assertThat(line.getFormattedMessage()).isEqualTo("Support request " + reference + " recorded as Team Reviews item " + request.id()
                    + " for account " + learner.accountId() + ": problem with 1 image(s)");
            assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("support.reference", reference).containsEntry("request.type", "problem");
            log.containsNone("Search page hangs", "Private steps", "Learner");
        }
        assertThat(request.kind()).isEqualTo("request");
        assertThat(request.requestType()).isEqualTo("problem");
        assertThat(request.reference()).isEqualTo(reference);
        assertThat(request.category()).isEqualTo("problem");
        assertThat(request.page()).isEqualTo("Support");
        assertThat(request.pageUrl()).isNull();
        assertThat(request.title()).isEqualTo("Search page hangs");
        assertThat(request.details()).isEqualTo("Private steps");
        assertThat(request.priority()).isEqualTo("medium");
        assertThat(request.status()).isEqualTo("new");
        assertThat(request.submitterRole()).isEqualTo("user");
        assertThat(request.reviewerName()).isEqualTo("Learner");
        assertThat(request.attachments()).extracting(Attachment::contentType).containsExactly("image/png");
        // Feedback and questions are ideas for the category list; the type says what they are.
        assertThat(reviews.recordRequest(learner, "feedback", "Nice", "Thanks", List.of(), UUID.randomUUID().toString()).category()).isEqualTo("idea");
        assertThat(reviews.recordRequest(learner, "question", "Why", "Because", List.of(), UUID.randomUUID().toString()).category()).isEqualTo("idea");
        // The same reference is recorded once, whatever the retry carries.
        assertThat(reviews.recordRequest(learner, "problem", "Retry", "Again", List.of(), reference).id()).isEqualTo(request.id());
        assertThat(reviews.list(owner, null).items()).hasSize(3);
        var review = create(first, "A review");

        var summary = reviews.list(owner, null).items().stream().filter(i -> i.id().equals(request.id())).findFirst().orElseThrow();
        assertThat(summary.kind()).isEqualTo("request");
        assertThat(summary.requestType()).isEqualTo("problem");
        assertThat(summary.reference()).isEqualTo(reference);
        assertThat(summary.attachmentCount()).isEqualTo(1);
        assertThat(reviews.list(owner, null).items().stream().filter(i -> i.id().equals(review.id())).findFirst().orElseThrow())
                .extracting(Summary::kind, Summary::requestType, Summary::reference).containsExactly("review", null, null);
        assertThat(reviews.item(second, request.id()).kind()).isEqualTo("request");
        assertThat(reviews.attachment(second, request.id(), request.attachments().getFirst().id()).contentType()).isEqualTo("image/png");
        // A reviewer sees reviews they wrote, never a request; the learner has no review access at all.
        assertThat(reviews.list(first, null).items()).extracting(Summary::id).containsExactly(review.id());
        assertFailure(() -> reviews.item(first, request.id()), 404);
        assertThat(reviews.list(learner, null).items()).isEmpty();
        assertFailure(() -> reviews.item(learner, request.id()), 404);

        // Triage: the owner any item; an admin a request only; a reviewer nothing.
        assertFailure(() -> reviews.triage(second, review.id(), "accepted", ""), 403);
        assertFailure(() -> reviews.triage(first, request.id(), "accepted", ""), 403);
        assertThat(reviews.triage(second, request.id(), "accepted", "Looking into it").ownerNote()).isEqualTo("Looking into it");
        assertThat(reviews.triage(owner, review.id(), "planned", "").status()).isEqualTo("planned");
        assertThat(reviews.triage(owner, request.id(), "done", "").status()).isEqualTo("done");
        // A request is never edited through the review API; the owner alone removes it (an admin or its writer cannot).
        assertFailure(() -> reviews.update(learner, request.id(), UUID.randomUUID(), fields("Edited"), List.of(), List.of()), 404);
        assertFailure(() -> reviews.update(owner, request.id(), UUID.randomUUID(), fields("Edited"), List.of(), List.of()), 404);
        assertFailure(() -> reviews.delete(second, request.id()), 404);
        assertFailure(() -> reviews.delete(learner, request.id()), 404);
        reviews.delete(owner, request.id());
        assertFailure(() -> reviews.item(owner, request.id()), 404);
        assertThat(Files.exists(root.resolve("support-requests/attachments").resolve(request.id()))).isFalse();
        assertFailure(() -> reviews.recordRequest(learner, "complaint", "x", "y", List.of(), UUID.randomUUID().toString()), 422);
        assertFailure(() -> reviews.recordRequest(learner, "problem", "x", "y", List.of(), " "), 422);
        assertFailure(() -> reviews.recordRequest(null, "problem", "x", "y", List.of(), "ref"), 401);
    }

    /** Without staff access (the legacy reviewer list) a request still records; the owner's own request says so. */
    @Test void withoutStaffAccessARequestRecordsTheRoleFromTheAuthorCapability() {
        var reference = UUID.randomUUID().toString();
        assertThat(reviews.recordRequest(learner, "feedback", "Idea", "Text", List.of(), reference).submitterRole()).isEqualTo("user");
        assertThat(reviews.recordRequest(owner, "feedback", "Idea", "Text", List.of(), UUID.randomUUID().toString()).submitterRole()).isEqualTo("owner");
        assertThat(reviews.list(owner, null).items()).extracting(Summary::kind).containsExactly("request", "request");
        assertThat(reviews.list(first, null).items()).isEmpty();
        try (var log = com.lookahead.domain.LogCapture.start()) {
            reviews.triage(owner, reviews.list(owner, null).items().getFirst().id(), "done", "");
            assertThat(log.only("platform-review.status").getFormattedMessage()).endsWith("status changed by the owner: new -> done");
        }
    }

    /** A day file written before DLV-971 has no kind, requestType or reference: it reads as reviews and keeps every field. */
    /** DLV-971 (owner, 2026-11-06): one record per item at records/YYYY/MM/DD/<ID>.json in the README's shape. */
    @Test void aReviewIsOneRecordUnderItsDayInTheApprovedShape() throws Exception {
        var sent = create(first, "Old item", png());
        assertThat(sent.id()).isEqualTo("REV-000001");
        Path record = root.resolve("reviews/records/2026/10/12/REV-000001.json");
        String file = Files.readString(record);
        assertThat(file).contains("\"schema\" : \"lookahead.review-record/v1\"", "\"kind\" : \"review\"", "\"requestType\" : null", "\"reference\" : null",
                "\"ticketId\" : null", "\"closedAt\" : null", "\"closedBy\" : null", "\"submitter\" : {", "\"accountId\" : \"" + first.accountId() + "\"",
                "\"role\" : \"reviewer\"", "\"displayName\" : \"First Reviewer\"", "\"path\" : \"attachments/REV-000001/01-screen.png\"");
        assertThat(file).doesNotContain("reviewerId", "reviewerAccountId");
        assertThat(Files.exists(root.resolve("reviews/attachments/REV-000001/01-screen.png"))).isTrue();
        var item = reviews.item(first, sent.id());
        assertThat(item.kind()).isEqualTo("review");
        assertThat(item.requestType()).isNull();
        assertThat(item.ticketId()).isNull();
        assertThat(item.ticketStatus()).isNull();
        assertThat(item.reviewerId()).isEqualTo(sent.reviewerId()).hasSize(24);
        assertThat(create(second, "Second").id()).isEqualTo("REV-000002");
        var request = reviews.recordRequest(learner, "question", "Q", "Body", List.of(), UUID.randomUUID().toString());
        assertThat(request.id()).isEqualTo("SUP-000001");
        assertThat(Files.exists(root.resolve("support-requests/records/2026/10/12/SUP-000001.json"))).isTrue();
    }

    /** DLV-969 (owner decision 2026-11-03): an admin sees who wrote an item as a role label; the owner sees the name. */
    @Test void adminsSeeTheWriterAsARoleLabelAndTheOwnerSeesTheName() {
        var access = mock(com.lookahead.domain.access.StaffAccessService.class);
        var aliases = mock(com.lookahead.domain.access.StaffAliases.class);
        reviews.configureStaffAccess(access);
        reviews.configureAliases(aliases);
        when(access.permissions(learner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("tester")));
        when(access.permissions(second)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("admin")));
        when(access.permissions(owner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(true, Set.of()));
        when(aliases.seesIdentities(owner)).thenReturn(true);
        when(aliases.label(learner.accountId())).thenReturn("Tester 3");
        var item = create(learner, "Tester finding");
        var mine = create(second, "Admin note");
        assertThat(reviews.list(second, null).items()).extracting(Summary::reviewerName).containsExactly("Second Reviewer", "Tester 3");
        assertThat(reviews.item(second, item.id()).reviewerName()).isEqualTo("Tester 3");
        assertFailure(() -> reviews.triage(second, item.id(), "accepted", ""), 403);
        assertThat(reviews.triage(owner, item.id(), "accepted", "").reviewerName()).isEqualTo("Learner");
        assertThat(reviews.item(second, mine.id()).reviewerName()).isEqualTo("Second Reviewer");
        assertThat(reviews.item(owner, item.id()).reviewerName()).isEqualTo("Learner");
        assertThat(reviews.item(learner, item.id()).reviewerName()).isEqualTo("Learner");
        // The stored file keeps the real name; only what an admin is sent changes.
        assertThat(reviews.list(owner, null).items()).extracting(Summary::reviewerName).contains("Learner");
    }

    @Test void accountsWithoutAReviewRoleAreDenied() {
        assertFailure(() -> reviews.viewer(learner), 403);
        assertFailure(() -> reviews.list(learner, null), 403);
        assertFailure(() -> reviews.create(learner, UUID.randomUUID(), fields("x"), List.of()), 403);
        assertFailure(() -> reviews.viewer(null), 401);
    }

    @Test void everyRecordLandsInItsDayFolderAndTheListIsNewestFirstAcrossBothStores() throws Exception {
        create(first, "One");
        create(second, "Two");
        try (var files = Files.list(root.resolve("reviews/records/2026/10/12"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("REV-000001.json", "REV-000002.json");
        }
        clock.set(Instant.parse("2026-10-13T08:00:00Z"));
        var next = create(first, "Three");
        var request = reviews.recordRequest(learner, "problem", "Request", "Body", List.of(), UUID.randomUUID().toString());
        assertThat(Files.exists(root.resolve("reviews/records/2026/10/13/REV-000003.json"))).isTrue();
        assertThat(Files.exists(root.resolve("support-requests/records/2026/10/13/SUP-000001.json"))).isTrue();
        assertThat(reviews.list(owner, null).items()).extracting(Summary::title).containsExactly("Request", "Three", "Two", "One");
        assertThat(reviews.list(first, null).items()).extracting(Summary::title).containsExactly("Three", "One");
        assertThat(reviews.item(first, next.id()).createdAt()).isEqualTo("2026-10-13T08:00:00Z");
        assertThat(reviews.item(owner, request.id()).kind()).isEqualTo("request");
    }

    @Test void theOwnersStatusLocksTheItemAndTheReviewerSeesTheNote() {
        var item = create(first, "Lesson typo");
        assertFailure(() -> reviews.triage(first, item.id(), "accepted", "x"), 403);
        var triaged = reviews.triage(owner, item.id(), "planned", "  Fixing in the next batch  ");
        assertThat(triaged.status()).isEqualTo("planned");
        assertThat(reviews.item(first, item.id()).ownerNote()).isEqualTo("Fixing in the next batch");
        assertThat(reviews.list(first, null).items().getFirst().ownerNote()).isTrue();

        var locked = catchThrowableOfType(AccountFailure.class,
                () -> reviews.update(first, item.id(), UUID.randomUUID(), fields("Changed"), List.of(), List.of()));
        assertThat(locked.status()).isEqualTo(409);
        assertThat(locked.code()).isEqualTo("PLATFORM_REVIEW_LOCKED");
        assertFailure(() -> reviews.delete(first, item.id()), 409);
        assertFailure(() -> reviews.triage(owner, item.id(), "unknown", ""), 422);
        assertFailure(() -> reviews.triage(owner, item.id(), "done", "x".repeat(2001)), 422);
    }

    @Test void editingKeepsChosenImagesAndRemovesTheRest() throws Exception {
        var item = create(first, "Two screenshots", png(), png());
        var keep = item.attachments().getFirst().id();
        var gone = item.attachments().get(1).id();
        var updated = reviews.update(first, item.id(), UUID.randomUUID(), fields("Now three"), List.of(keep), List.of(upload("image/jpeg", jpegWithExif())));
        assertThat(updated.title()).isEqualTo("Now three");
        assertThat(updated.attachments()).extracting(Attachment::contentType).containsExactly("image/png", "image/jpeg");
        assertFailure(() -> reviews.attachment(first, item.id(), gone), 404);
        try (var files = Files.list(root.resolve("reviews/attachments").resolve(item.id()))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("01-screen.png", "03-screen.jpg");
        }
        assertFailure(() -> reviews.update(first, item.id(), UUID.randomUUID(), fields("x"), List.of(gone), List.of()), 422);
    }

    @Test void deletingRemovesTheRecordAndItsImages() {
        var item = create(first, "Remove me", png());
        reviews.delete(first, item.id());
        assertThat(reviews.list(owner, null).items()).isEmpty();
        assertThat(Files.exists(root.resolve("reviews/attachments").resolve(item.id()))).isFalse();
        assertThat(Files.exists(root.resolve("reviews/records/2026/10/12/" + item.id() + ".json"))).isFalse();
        assertFailure(() -> reviews.item(owner, item.id()), 404);
    }

    @Test void aRetriedCreateReturnsTheFirstItemAndADifferentPayloadConflicts() {
        var key = UUID.randomUUID();
        var created = reviews.create(first, key, fields("Once"), List.of(upload("image/png", png())));
        var replay = reviews.create(first, key, fields("Once"), List.of(upload("image/png", png())));
        assertThat(created.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.item().id()).isEqualTo(created.item().id());
        assertThat(reviews.list(owner, null).items()).hasSize(1);
        assertFailure(() -> reviews.create(first, key, fields("Different"), List.of()), 409);
    }

    @Test void concurrentSavesOnOneDayAreAllKept() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Item>>();
            for (int i = 0; i < 24; i++) {
                var actor = i % 2 == 0 ? first : second;
                String title = "Concurrent " + i;
                tasks.add(() -> create(actor, title));
            }
            for (var result : pool.invokeAll(tasks)) result.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(reviews.list(owner, null).items()).hasSize(24);
        assertThat(reviews.list(first, null).items()).hasSize(12);
    }

    @Test void imagesAreCheckedBySignatureAndLocationMetadataIsRemoved() {
        var item = create(first, "Metadata", png(true), jpegWithExif(), webpWithExif());
        for (var attachment : item.attachments())
            assertThat(contains(reviews.attachment(first, item.id(), attachment.id()).bytes(), SECRET)).isFalse();
        byte[] webp = reviews.attachment(first, item.id(), item.attachments().get(2).id()).bytes();
        assertThat(webp[20] & 0x08).isZero(); // VP8X EXIF flag cleared
        assertThat(PlatformReviewImages.detect(webp)).isEqualTo("image/webp");

        assertFailure(() -> reviews.create(first, UUID.randomUUID(), fields("Spoof"), List.of(upload("image/png", jpegWithExif()))), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), fields("Text"), List.of(upload("image/png", "not an image".getBytes()))), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), fields("Svg"), List.of(upload("image/svg+xml", "<svg/>".getBytes()))), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), fields("Big"), List.of(upload("image/png", padded(png(), 5 * 1024 * 1024 + 1)))), 413);
        var seven = Collections.nCopies(7, upload("image/png", png()));
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), fields("Seven"), seven), 422);
        var five = Collections.nCopies(5, upload("image/png", padded(png(), 4_500_000)));
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), fields("Total"), five), 413);
        assertThat(reviews.list(owner, null).items()).hasSize(1);
    }

    @Test void fieldsFollowTheSameRulesAsTheForm() {
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), new Fields("bug", "Home", "", "t", "d", "high"), List.of()), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), new Fields("problem", "Elsewhere", "", "t", "d", "high"), List.of()), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), new Fields("problem", "Home", "https://evil.test", "t", "d", "high"), List.of()), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), new Fields("problem", "Home", "//evil.test", "t", "d", "high"), List.of()), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), new Fields("problem", "Home", "", "   ", "d", "high"), List.of()), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), new Fields("problem", "Home", "", "x".repeat(121), "d", "high"), List.of()), 422);
        assertFailure(() -> reviews.create(first, UUID.randomUUID(), new Fields("problem", "Home", "", "t", "x".repeat(5001), "high"), List.of()), 422);
        assertFailure(() -> reviews.create(first, null, fields("No key"), List.of()), 422);
        var ok = reviews.create(first, UUID.randomUUID(), new Fields("idea", "Lesson Reader", "/learn/core-java", "  Title  ", "Line one\nLine two", "low"), List.of());
        assertThat(ok.item().title()).isEqualTo("Title");
        assertThat(ok.item().details()).isEqualTo("Line one\nLine two");
    }

    @Test void theListIsPagedThirtyDaysAtATime() {
        for (int day = 0; day < 31; day++) {
            clock.set(Instant.parse("2026-09-01T12:00:00Z").plus(Duration.ofDays(day)));
            create(first, "Day " + day);
        }
        var page = reviews.list(owner, null);
        assertThat(page.items()).hasSize(30);
        assertThat(page.items().getFirst().title()).isEqualTo("Day 30");
        assertThat(page.nextBefore()).isEqualTo("2026-09-02");
        var older = reviews.list(owner, page.nextBefore());
        assertThat(older.items()).extracting(Summary::title).containsExactly("Day 0");
        assertThat(older.nextBefore()).isNull();
        assertFailure(() -> reviews.list(owner, "not-a-date"), 422);
    }

    @Test void theRenamedBlazeCatalogPageAcceptsAndRewritesTheOldName() throws Exception {
        assertThat(PAGES).contains("Blaze Catalog").doesNotContain("Look Ahead Catalog");
        var sent = reviews.create(first, UUID.randomUUID(),
                new Fields("problem", "Look Ahead Catalog", "/look-ahead", "Old tab", "Steps to reproduce", "high"), List.of()).item();
        assertThat(sent.page()).isEqualTo("Blaze Catalog");
        assertThat(sent.pageUrl()).isEqualTo("/look-ahead");

        // The record stores the current name; a legacy day file with the old name is repaired by the import (LegacyReviewImportTest).
        Path record = root.resolve("reviews/records/2026/10/12/" + sent.id() + ".json");
        assertThat(Files.readString(record)).contains("\"Blaze Catalog\"").doesNotContain("Look Ahead Catalog");
        assertThat(reviews.list(owner, null).items()).extracting(Summary::page).containsExactly("Blaze Catalog");
    }

    /** DLV-969 (owner, 2026-11-04): a damaged record makes the review pages answer 503; the log says which record and why. */
    @Test void aDamagedRecordIsExplainedOnceWithoutItsContents() throws Exception {
        Path record = root.resolve("reviews/records/2026/10/12/REV-000009.json");
        Files.createDirectories(record.getParent());
        Files.writeString(record, "{\"schema\":\"other/v1\",\"id\":\"REV-000009\"}");
        try (var log = com.lookahead.domain.LogCapture.start()) {
            assertFailure(() -> reviews.list(owner, null), 503);
            assertFailure(() -> reviews.list(owner, null), 503);
            Files.writeString(record, "{ not json private-review-text");
            assertFailure(() -> reviews.list(owner, null), 503);
            var lines = log.lines("platform-review.read");
            assertThat(lines).extracting(line -> com.lookahead.domain.LogCapture.keys(line).get("reason")).containsExactly("RECORD_SCHEMA", "RECORD_UNREADABLE");
            assertThat(lines.getFirst().getFormattedMessage()).startsWith("Record REV-000009 cannot be read: it is not a ")
                    .endsWith("; review pages answer 503 until it is repaired");
            assertThat(lines.getLast().getFormattedMessage()).startsWith("Record REV-000009 cannot be read: it is not valid JSON for a record (");
            assertThat(lines).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
            log.containsNone("private-review-text");
        }
    }

    @Test void unconfiguredStorageAnswersUnavailable() {
        var author = mock(LocalAuthorAccess.class);
        when(author.allowed(owner)).thenReturn(true);
        var settings = new PlatformReviewConfiguration.StoreSettings("", "", "", "reviews/", "support-requests/", "delivery/", "");
        var publication = com.lookahead.domain.publication.PublicationSettings.from(new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment","local"));
        var mapper = JsonMapper.builder().build();
        var numbers = new ReviewRecordNumbers.InMemory();
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<software.amazon.awssdk.services.s3.S3Client> clients = mock(org.springframework.beans.factory.ObjectProvider.class);
        var stores = new PlatformReviewService.Stores(
                new RecordStore(PlatformReviewConfiguration.objects(settings, publication, clients, "reviews/"), KIND_REVIEW, mapper, numbers),
                new RecordStore(PlatformReviewConfiguration.objects(settings, publication, clients, "support-requests/"), KIND_REQUEST, mapper, numbers));
        assertThat(PlatformReviewConfiguration.objects(settings, publication, clients, null)).isNull();
        var service = new PlatformReviewService(stores, author, mapper, "", clock, history);
        assertFailure(() -> service.list(owner, null), 503);
        assertFailure(() -> service.recordRequest(owner, "question", "Q", "Body", List.of(), "ref-1"), 503);
    }

    /** DLV-969: submitted, status changed and deleted are one INFO line each; never the title, details or the reviewer's name. */
    @Test void reviewActionsAreLoggedWithIdsAndFixedValuesOnly() {
        try (var log = com.lookahead.domain.LogCapture.start()) {
            var item = create(first, "Secret title text", png());
            var submitted = log.only("platform-review.submit");
            assertThat(submitted.getFormattedMessage()).isEqualTo("Platform review item " + item.id() + " submitted by account " + first.accountId()
                    + ": problem on page Search, priority high, 1 image(s)");
            assertThat(com.lookahead.domain.LogCapture.keys(submitted)).containsEntry("target.id", item.id()).containsEntry("count", 1)
                    .containsEntry("review.category", "problem");
            reviews.triage(owner, item.id(), "planned", "private owner note");
            assertThat(log.only("platform-review.status").getFormattedMessage())
                    .isEqualTo("Platform review item " + item.id() + " status changed by the owner: new -> planned");
            var other = create(second, "Another");
            reviews.delete(second, other.id());
            assertThat(log.only("platform-review.delete").getFormattedMessage())
                    .isEqualTo("Platform review item " + other.id() + " deleted by account " + second.accountId() + " with its 0 image(s)");
            log.containsNone("Secret title", "Steps to reproduce", "private owner note", "First Reviewer", "first@example.test");
        }
    }

    /** DLV-969: the daily item limit and the image limits are one lookahead.limit line each. */
    @Test void dailyAndImageLimitsAreLimitLines() {
        for (int i = 0; i < PlatformReviewService.ITEMS_PER_REVIEWER_PER_DAY; i++) create(first, "Item " + i);
        try (var log = com.lookahead.domain.LogCapture.start()) {
            assertFailure(() -> create(first, "One too many"), 422);
            var daily = log.only("platform-review.submit");
            assertThat(daily.getLoggerName()).isEqualTo("lookahead.limit");
            assertThat(com.lookahead.domain.LogCapture.keys(daily)).containsEntry("reason", "PLATFORM_REVIEW_DAILY_LIMIT")
                    .containsEntry("limit.max", 100L).containsEntry("limit.window", "1d UTC").containsEntry("account.id", first.accountId());
            assertFailure(() -> create(second, "Too many images", png(), png(), png(), png(), png(), png(), png()), 422);
            assertThat(com.lookahead.domain.LogCapture.keys(log.only("platform-review.images"))).containsEntry("limit.name", "platform-review-images")
                    .containsEntry("limit.max", 6L).containsEntry("reason", "PLATFORM_REVIEW_INVALID");
        }
    }

    // ---- DLV-971 (owner, 2026-11-06): promote, closed/reopen, the seven-day close, the ticket status beside the record

    com.lookahead.domain.access.StaffAccessService staff() {
        var access = mock(com.lookahead.domain.access.StaffAccessService.class);
        reviews.configureStaffAccess(access);
        when(access.permissions(owner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(true, Set.of()));
        when(access.permissions(first)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("reviewer")));
        when(access.permissions(second)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("admin")));
        when(access.permissions(learner)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of()));
        return access;
    }

    com.lookahead.domain.delivery.DeliveryPlanService delivery(com.lookahead.domain.access.StaffAccessService access) throws Exception {
        PlatformReviewFixtures.seedPlan(root);
        var plans = PlatformReviewFixtures.delivery(root, JsonMapper.builder().build(), access, history, clock, "local");
        reviews.configureDelivery(plans);
        return plans;
    }

    @Test void promoteAddsATicketWithOriginAndLinksTheRecordOnceOnly() throws Exception {
        var access = staff();
        var plans = delivery(access);
        var review = create(first, "Search misses Kafka", png());
        var request = reviews.recordRequest(learner, "question", "How do plans renew?", "Asked on Support", List.of(), UUID.randomUUID().toString());
        // A reviewer never promotes; an admin promotes a request only; the owner promotes anything.
        assertFailure(() -> reviews.promote(first, review.id()), 403);
        assertFailure(() -> reviews.promote(second, review.id()), 403);
        Promoted promoted;
        try (var log = com.lookahead.domain.LogCapture.start()) {
            promoted = reviews.promote(owner, review.id());
            var line = log.only("platform-review.promote");
            assertThat(line.getFormattedMessage()).isEqualTo("Platform review item " + review.id() + " promoted by the owner to delivery ticket DLV-2 (plan version 8)");
            log.containsNone("Search misses Kafka");
        }
        assertThat(promoted.ticketId()).isEqualTo("DLV-2");
        var ticket = promoted.item();
        assertThat(ticket.path("title").asString()).isEqualTo("Search misses Kafka");
        assertThat(ticket.path("summary").asString()).isEqualTo("Steps to reproduce");
        assertThat(ticket.path("type").asString()).isEqualTo("bug");
        assertThat(ticket.path("priorityId").asString()).isEqualTo("P1");
        assertThat(ticket.path("statusId").asString()).isEqualTo("backlog");
        assertThat(ticket.path("stageId").asString()).isEqualTo("stage-1");
        assertThat(ticket.path("origin").path("id").asString()).isEqualTo(review.id());
        assertThat(ticket.path("origin").path("kind").asString()).isEqualTo("review");
        assertThat(ticket.path("createdIn").asString()).isEqualTo("local");
        assertThat(ticket.path("updatedIn").asString()).isEqualTo("local");
        assertThat(ticket.path("updatedAt").asString()).isEqualTo("2026-10-12T09:00:00Z");
        assertThat(ticket.path("pendingLocal").isNull()).isTrue();
        assertThat(history.rows).extracting(ReviewStatusHistory.InMemory.Row::recordId, ReviewStatusHistory.InMemory.Row::kind, ReviewStatusHistory.InMemory.Row::toStatus)
                .contains(org.assertj.core.groups.Tuple.tuple("DLV-2", "ticket", "backlog"));
        // The record carries the ticket and shows the ticket's status from the plan.
        var linked = reviews.item(first, review.id());
        assertThat(linked.ticketId()).isEqualTo("DLV-2");
        assertThat(linked.ticketStatus()).isEqualTo("backlog");
        assertThat(reviews.list(owner, null).items().stream().filter(i -> i.id().equals(review.id())).findFirst().orElseThrow().ticketStatus()).isEqualTo("backlog");
        assertThat(plans.plan(owner).path("workItems").size()).isEqualTo(2);
        var again = catchThrowableOfType(AccountFailure.class, () -> reviews.promote(owner, review.id()));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("PLATFORM_REVIEW_ALREADY_PROMOTED");
        // An admin promotes the learner's request: a task from a question, medium priority -> P2.
        var fromRequest = reviews.promote(second, request.id());
        assertThat(fromRequest.ticketId()).isEqualTo("DLV-3");
        assertThat(fromRequest.item().path("type").asString()).isEqualTo("task");
        assertThat(fromRequest.item().path("priorityId").asString()).isEqualTo("P2");
        assertThat(fromRequest.item().path("origin").path("kind").asString()).isEqualTo("request");
        assertThat(reviews.item(owner, request.id()).ticketId()).isEqualTo("DLV-3");
        assertFailure(() -> reviews.promote(owner, "REV-999999"), 404);
        assertFailure(() -> reviews.promote(learner, review.id()), 403);
    }

    @Test void promoteWithoutADeliveryPlanAnswersUnavailableAndTheRecordStaysUnlinked() {
        staff();
        var review = create(first, "No plan yet");
        assertFailure(() -> reviews.promote(owner, review.id()), 503);
        assertThat(reviews.item(owner, review.id()).ticketId()).isNull();
        assertThat(reviews.item(owner, review.id()).ticketStatus()).isNull();
    }

    @Test void ticketTypesComeFromTheKindAndRequestType() throws Exception {
        staff();
        delivery(null);
        var problem = reviews.recordRequest(learner, "problem", "Broken", "Steps", List.of(), UUID.randomUUID().toString());
        var feedback = reviews.recordRequest(learner, "feedback", "Idea", "Text", List.of(), UUID.randomUUID().toString());
        assertThat(reviews.promote(owner, problem.id()).item().path("type").asString()).isEqualTo("bug");
        assertThat(reviews.promote(owner, feedback.id()).item().path("type").asString()).isEqualTo("story");
        var content = reviews.create(first, UUID.randomUUID(), new Fields("content", "Lesson Reader", "", "Typo", "Fix it", "low"), List.of()).item();
        var change = reviews.create(first, UUID.randomUUID(), new Fields("change", "Search", "", "Reorder", "Please", "medium"), List.of()).item();
        assertThat(reviews.promote(owner, content.id()).item().path("type").asString()).isEqualTo("task");
        assertThat(reviews.promote(owner, change.id()).item().path("priorityId").asString()).isEqualTo("P2");
        assertThat(reviews.item(owner, content.id()).ticketStatus()).isEqualTo("backlog");
    }

    @Test void closedIsAStatusTheOwnerAndAdminsSetAndReopenWithAHistoryRow() {
        staff();
        var review = create(first, "Closable");
        var request = reviews.recordRequest(learner, "feedback", "Idea", "Text", List.of(), UUID.randomUUID().toString());
        clock.set(Instant.parse("2026-10-12T10:00:00Z"));
        var closed = reviews.triage(second, request.id(), "closed", "Answered by email");
        assertThat(closed.status()).isEqualTo("closed");
        assertThat(closed.closedAt()).isEqualTo("2026-10-12T10:00:00Z");
        assertThat(closed.closedBy()).isEqualTo("admin");
        assertThat(closed.updatedAt()).isEqualTo("2026-10-12T10:00:00Z");
        // Reopen: closed -> any status by the same rights; the close stamps are cleared.
        var reopened = reviews.triage(second, request.id(), "accepted", "Looking again");
        assertThat(reopened.status()).isEqualTo("accepted");
        assertThat(reopened.closedAt()).isNull();
        assertThat(reopened.closedBy()).isNull();
        assertFailure(() -> reviews.triage(second, review.id(), "closed", ""), 403);
        assertThat(reviews.triage(owner, review.id(), "closed", "").closedBy()).isEqualTo("owner");
        assertFailure(() -> reviews.triage(first, review.id(), "new", ""), 403);
        assertThat(history.rows).extracting(ReviewStatusHistory.InMemory.Row::recordId, ReviewStatusHistory.InMemory.Row::kind, ReviewStatusHistory.InMemory.Row::actorId,
                        ReviewStatusHistory.InMemory.Row::fromStatus, ReviewStatusHistory.InMemory.Row::toStatus, ReviewStatusHistory.InMemory.Row::noteSet)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(request.id(), "request", second.accountId(), "new", "closed", true),
                        org.assertj.core.groups.Tuple.tuple(request.id(), "request", second.accountId(), "closed", "accepted", true),
                        org.assertj.core.groups.Tuple.tuple(review.id(), "review", owner.accountId(), "new", "closed", false));
        // The same status again is a note change only: no history row.
        reviews.triage(owner, review.id(), "closed", "Still closed");
        assertThat(history.rows).hasSize(3);
    }

    /** The sweep: requests new or accepted with no update for seven days are closed by "system"; reviews and other statuses never. */
    @Test void staleSupportRequestsAreClosedBySystemAfterSevenDays() {
        staff();
        var stale = reviews.recordRequest(learner, "problem", "Old", "Text", List.of(), UUID.randomUUID().toString());
        var accepted = reviews.recordRequest(learner, "question", "Older", "Text", List.of(), UUID.randomUUID().toString());
        reviews.triage(owner, accepted.id(), "accepted", "On it");
        var done = reviews.recordRequest(learner, "feedback", "Done", "Text", List.of(), UUID.randomUUID().toString());
        reviews.triage(owner, done.id(), "done", "");
        var review = create(first, "Never swept");
        clock.set(Instant.parse("2026-10-18T09:00:00Z"));
        var fresh = reviews.recordRequest(learner, "problem", "Fresh", "Text", List.of(), UUID.randomUUID().toString());
        // Six days: nothing is due yet.
        assertThat(reviews.closeStaleRequests()).isZero();
        clock.set(Instant.parse("2026-10-19T09:00:01Z"));
        history.rows.clear();
        int closed;
        try (var log = com.lookahead.domain.LogCapture.start()) {
            closed = reviews.closeStaleRequests();
            assertThat(log.only("platform-review.sweep").getFormattedMessage()).isEqualTo("Support request sweep closed 2 request(s) with no update in 7 days");
            assertThat(com.lookahead.domain.LogCapture.keys(log.only("platform-review.sweep"))).containsEntry("count", 2);
            assertThat(log.lines("platform-review.auto-close")).extracting(line -> com.lookahead.domain.LogCapture.keys(line).get("target.id")).containsExactlyInAnyOrder(stale.id(), accepted.id());
            log.containsNone("Old", "Older", "Learner");
        }
        assertThat(closed).isEqualTo(2);
        var swept = reviews.item(owner, stale.id());
        assertThat(swept.status()).isEqualTo("closed");
        assertThat(swept.ownerNote()).isEqualTo(PlatformReviewService.AUTO_CLOSE_NOTE);
        assertThat(swept.closedBy()).isEqualTo("system");
        assertThat(swept.closedAt()).isEqualTo("2026-10-19T09:00:01Z");
        assertThat(reviews.item(owner, accepted.id()).status()).isEqualTo("closed");
        assertThat(reviews.item(owner, done.id()).status()).isEqualTo("done");
        assertThat(reviews.item(owner, fresh.id()).status()).isEqualTo("new");
        assertThat(reviews.item(owner, review.id()).status()).isEqualTo("new");
        assertThat(history.rows).extracting(ReviewStatusHistory.InMemory.Row::recordId, ReviewStatusHistory.InMemory.Row::actorId, ReviewStatusHistory.InMemory.Row::toStatus, ReviewStatusHistory.InMemory.Row::noteSet)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(stale.id(), null, "closed", true), org.assertj.core.groups.Tuple.tuple(accepted.id(), null, "closed", true));
        // A second sweep finds nothing; a reopened request starts its seven days again.
        assertThat(reviews.closeStaleRequests()).isZero();
        reviews.triage(owner, stale.id(), "new", "Reopened");
        assertThat(reviews.closeStaleRequests()).isZero();
        // Nine days on: the reopened request and the once-fresh one are both stale.
        clock.set(Instant.parse("2026-10-27T09:00:01Z"));
        assertThat(reviews.closeStaleRequests()).isEqualTo(2);
        assertThat(reviews.item(owner, fresh.id()).closedBy()).isEqualTo("system");
    }

    @Test void attachmentNumbersContinuePastTheHighestKept() {
        var attachments = List.of(new StoredAttachment("a", "x.png", "image/png", 1, "h", "attachments/REV-000001/01-x.png"),
                new StoredAttachment("b", "y.png", "image/png", 1, "h", "attachments/REV-000001/04-y.png"));
        assertThat(PlatformReviewService.nextIndex(attachments)).isEqualTo(5);
        assertThat(PlatformReviewService.nextIndex(List.of())).isEqualTo(1);
        assertThat(RecordStore.attachmentKey("SUP-000001", 2, "My Screen Shot (final).PNG", "png")).isEqualTo("attachments/SUP-000001/02-my-screen-shot-final.png");
        assertThat(RecordStore.attachmentKey("SUP-000001", 1, "", "jpg")).isEqualTo("attachments/SUP-000001/01-image.jpg");
        assertThat(RecordStore.attachmentKey("SUP-000001", 1, "x".repeat(80) + ".png", "webp")).isEqualTo("attachments/SUP-000001/01-" + "x".repeat(40) + ".webp");
        assertThat(RecordStore.key("REV-000012", "2026-01-05T23:59:59Z")).isEqualTo("records/2026/01/05/REV-000012.json");
    }

    private Item create(AccountPrincipal actor, String title, byte[]... pngs) {
        var uploads = new ArrayList<Upload>();
        for (byte[] bytes : pngs) uploads.add(upload(PlatformReviewImages.detect(bytes), bytes));
        return reviews.create(actor, UUID.randomUUID(), fields(title), uploads).item();
    }

    private static Fields fields(String title) {
        return new Fields("problem", "Search", "/search", title, "Steps to reproduce", "high");
    }

    private static Upload upload(String type, byte[] bytes) { return new Upload("screen.png", type, bytes); }

    private static byte[] padded(byte[] image, int size) { return Arrays.copyOf(image, size); }

    private static void assertFailure(ThrowableAssert.ThrowingCallable call, int status) {
        assertThat(catchThrowableOfType(AccountFailure.class, call)).isNotNull().extracting(AccountFailure::status).isEqualTo(status);
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
