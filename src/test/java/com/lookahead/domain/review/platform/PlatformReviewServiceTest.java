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
    PlatformReviewService reviews;

    @BeforeEach void setup() {
        var author = mock(LocalAuthorAccess.class);
        when(author.allowed(owner)).thenReturn(true);
        reviews = new PlatformReviewService(new FileSystemPlatformReviewObjects(root), author, JsonMapper.builder().build(),
                "first@example.test, second@example.test", clock);
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
        assertThat(Files.exists(root.resolve("review-images").resolve(request.id()))).isFalse();
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
    @Test void aDayFileWithoutTheKindFieldsReadsAsReviews() throws Exception {
        var sent = create(first, "Old item");
        Path day = root.resolve("reviews/2026-10-12.json");
        String file = Files.readString(day);
        assertThat(file).contains("\"kind\" : \"review\"", "\"requestType\" : null", "\"reference\" : null");
        var mapper = JsonMapper.builder().build();
        var tree = mapper.readTree(file);
        for (var node : tree.path("items")) ((tools.jackson.databind.node.ObjectNode) node).remove(List.of("kind", "requestType", "reference"));
        Files.writeString(day, mapper.writeValueAsString(tree));
        assertThat(Files.readString(day)).doesNotContain("\"kind\"", "\"requestType\"", "\"reference\"");
        var item = reviews.item(first, sent.id());
        assertThat(item.kind()).isEqualTo("review");
        assertThat(item.requestType()).isNull();
        assertThat(item.reference()).isNull();
        assertThat(item.title()).isEqualTo("Old item");
        assertThat(reviews.list(owner, null).items().getFirst().kind()).isEqualTo("review");
        reviews.triage(owner, sent.id(), "accepted", "");
        assertThat(Files.readString(day)).contains("\"kind\" : \"review\"");
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

    @Test void everyReviewOfADayIsAppendedToThatDaysFileAndANewDayStartsANewFile() throws Exception {
        create(first, "One");
        create(second, "Two");
        assertThat(Files.list(root.resolve("reviews")).map(p -> p.getFileName().toString())).containsExactly("2026-10-12.json");
        String file = Files.readString(root.resolve("reviews/2026-10-12.json"));
        assertThat(file).contains("\"schema\" : \"lookahead.platform-review-day/v1\"", "\"title\" : \"One\"", "\"title\" : \"Two\"");

        clock.set(Instant.parse("2026-10-13T08:00:00Z"));
        var next = create(first, "Three");
        assertThat(Files.list(root.resolve("reviews")).map(p -> p.getFileName().toString()))
                .containsExactlyInAnyOrder("2026-10-12.json", "2026-10-13.json");
        assertThat(reviews.list(owner, null).items()).extracting(Summary::title).containsExactly("Three", "Two", "One");
        assertThat(reviews.item(first, next.id()).createdAt()).isEqualTo("2026-10-13T08:00:00Z");
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
        try (var files = Files.list(root.resolve("review-images").resolve(item.id()))) { assertThat(files.count()).isEqualTo(2); }
        assertFailure(() -> reviews.update(first, item.id(), UUID.randomUUID(), fields("x"), List.of(gone), List.of()), 422);
    }

    @Test void deletingRemovesTheRecordAndItsImages() {
        var item = create(first, "Remove me", png());
        reviews.delete(first, item.id());
        assertThat(reviews.list(owner, null).items()).isEmpty();
        assertThat(Files.exists(root.resolve("review-images").resolve(item.id()))).isFalse();
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

        // A day file saved before the rename reads as the new page name and keeps every other field.
        Path day = root.resolve("reviews/2026-10-12.json");
        Files.writeString(day, Files.readString(day).replace("\"Blaze Catalog\"", "\"Look Ahead Catalog\""));
        assertThat(reviews.item(first, sent.id()).page()).isEqualTo("Blaze Catalog");
        assertThat(reviews.list(owner, null).items()).extracting(Summary::page).containsExactly("Blaze Catalog");
        reviews.triage(owner, sent.id(), "accepted", "");
        assertThat(Files.readString(day)).contains("\"Blaze Catalog\"").doesNotContain("Look Ahead Catalog");
    }

    /** DLV-969 (owner, 2026-11-04): a damaged day file makes the review pages answer 503; the log says which file and why. */
    @Test void aDamagedDayFileIsExplainedOnceWithoutItsContents() throws Exception {
        Files.createDirectories(root.resolve("reviews"));
        Files.writeString(root.resolve("reviews/2026-10-12.json"), "{\"schema\":\"other/v1\",\"day\":\"2026-10-12\",\"items\":[]}");
        try (var log = com.lookahead.domain.LogCapture.start()) {
            assertFailure(() -> reviews.list(owner, null), 503);
            assertFailure(() -> reviews.list(owner, null), 503);
            Files.writeString(root.resolve("reviews/2026-10-12.json"), "{ not json private-review-text");
            assertFailure(() -> reviews.list(owner, null), 503);
            var lines = log.lines("platform-review.read");
            assertThat(lines).extracting(line -> com.lookahead.domain.LogCapture.keys(line).get("reason")).containsExactly("DAY_FILE_SCHEMA", "DAY_FILE_UNREADABLE");
            assertThat(lines.getFirst().getFormattedMessage()).startsWith("A platform review day file (2026-10-12) cannot be read: it is not a ")
                    .endsWith("; review pages answer 503 until it is repaired");
            assertThat(lines.getLast().getFormattedMessage()).startsWith("A platform review day file cannot be read: it is not valid JSON for a review day file (");
            assertThat(lines).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
            log.containsNone("private-review-text");
        }
    }

    @Test void unconfiguredStorageAnswersUnavailable() {
        var author = mock(LocalAuthorAccess.class);
        when(author.allowed(owner)).thenReturn(true);
        var missing = new PlatformReviewConfiguration().platformReviewObjects("", "", "", "",
                com.lookahead.domain.publication.PublicationSettings.from(new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment","local")),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        var service = new PlatformReviewService(missing, author, JsonMapper.builder().build(), "", clock);
        assertFailure(() -> service.list(owner, null), 503);
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
