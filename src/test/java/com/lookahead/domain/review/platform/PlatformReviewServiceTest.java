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
        assertThat(reviews.update(learner, item.id(), UUID.randomUUID(), fields("Updated finding"), List.of(), List.of()).submitterRole()).isEqualTo("tester");
        when(access.permissions(first)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("reviewer")));
        assertFailure(() -> reviews.item(first, item.id()), 404);
        assertFailure(() -> reviews.triage(first, item.id(), "accepted", ""), 403);
        when(access.permissions(second)).thenReturn(com.lookahead.domain.access.StaffPermissions.forRoles(false, Set.of("admin")));
        assertThat(reviews.list(second, null).items()).extracting(Summary::id).containsExactly(item.id());
        assertThat(reviews.triage(second, item.id(), "accepted", "Investigating").submitterRole()).isEqualTo("tester");
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

    @Test void unconfiguredStorageAnswersUnavailable() {
        var author = mock(LocalAuthorAccess.class);
        when(author.allowed(owner)).thenReturn(true);
        var missing = new PlatformReviewConfiguration().platformReviewObjects("", "", "", "",
                com.lookahead.domain.publication.PublicationSettings.from(new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment","local")),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        var service = new PlatformReviewService(missing, author, JsonMapper.builder().build(), "", clock);
        assertFailure(() -> service.list(owner, null), 503);
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
