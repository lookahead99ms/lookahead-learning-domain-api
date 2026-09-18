package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.model.PlanRecord;
import com.lookahead.learning.content.model.PlanVersion;
import com.lookahead.learning.content.repository.PlanRepository;
import com.lookahead.learning.content.util.PlanJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class PlanCardListingTest {
    private static final UUID OWNER = UUID.fromString("33b6c7d5-b25a-4937-a04e-364957273490");
    private final ObjectMapper mapper = new ObjectMapper();
    private PlanRepository repository;
    private PlanService service;

    @BeforeEach
    void createService() {
        repository = mock(PlanRepository.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        // Listing must use saved metadata and cannot require catalog validation or selection.
        service = new PlanService(repository, mapper, null, new PlanJson(mapper));
    }

    @Test
    void retainsMoreThanFourPlansAndUsesOwnerScopedVersionsWithoutLoadingSentinel() {
        List<PlanRecord> records = records(8);
        when(repository.list(OWNER, 7, 0)).thenReturn(records.subList(0, 7));
        for (PlanRecord record : records.subList(0, 6)) stubVersion(record);

        JsonNode first = service.list(OWNER, 6, null);
        assertThat(first.path("plans").size()).isEqualTo(6);
        assertThat(decode(first.path("nextCursor").asText())).isEqualTo("6");
        verify(repository).list(OWNER, 7, 0);
        for (int index = 0; index < 6; index++) {
            PlanRecord record = records.get(index);
            assertThat(first.path("plans").get(index).path("planId").asText()).isEqualTo(record.id().toString());
            verify(repository).findVersion(OWNER, record.id(), record.version());
        }
        // This also forbids locks, receipt reads, writes, and loading the seventh sentinel version.
        verifyNoMoreInteractions(repository);

        when(repository.list(OWNER, 7, 6)).thenReturn(records.subList(6, 8));
        for (PlanRecord record : records.subList(6, 8)) stubVersion(record);
        JsonNode second = service.list(OWNER, 6, first.path("nextCursor").asText());
        assertThat(second.path("plans").size()).isEqualTo(2);
        assertThat(second.path("nextCursor").isNull()).isTrue();
        verify(repository).list(OWNER, 7, 6);
        for (int index = 0; index < 2; index++) {
            PlanRecord record = records.get(index + 6);
            assertThat(second.path("plans").get(index).path("planId").asText()).isEqualTo(record.id().toString());
            verify(repository).findVersion(OWNER, record.id(), record.version());
        }
        verifyNoMoreInteractions(repository);
    }

    @Test
    void additiveCardRetainsIdentifiersRevisionGoalAndTimestamps() {
        PlanRecord record = records(1).getFirst();
        when(repository.list(OWNER, 21, 0)).thenReturn(List.of(record));
        stubVersion(record);
        JsonNode row = service.list(OWNER, 20, null).path("plans").get(0);

        assertThat(row.path("planId").asText()).isEqualTo(record.id().toString());
        assertThat(row.path("versionId").asText()).isEqualTo(record.version().toString());
        assertThat(row.path("revision").asLong()).isEqualTo(record.revision());
        assertThat(row.path("goal").asText()).isEqualTo(record.goal());
        assertThat(row.path("createdAt").asText()).isEqualTo(record.createdAt());
        assertThat(row.path("updatedAt").asText()).isEqualTo(record.updatedAt());
        assertThat(row.path("card").path("schemaVersion").asText()).isEqualTo("plan-card/v1");
        assertThat(row.path("card").path("metadataStatus").asText()).isEqualTo("available");
        assertThat(row.has("snapshot")).isFalse();
        assertThat(row.has("progress")).isFalse();
        verify(repository).list(OWNER, 21, 0);
        verify(repository).findVersion(OWNER, record.id(), record.version());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void emptyPageDoesNotReadAnyVersionsOrSelectAPlan() {
        when(repository.list(OWNER, 21, 0)).thenReturn(List.of());
        JsonNode response = service.list(OWNER, 20, null);
        assertThat(response.path("plans").isEmpty()).isTrue();
        assertThat(response.path("nextCursor").isNull()).isTrue();
        verify(repository).list(OWNER, 21, 0);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void invalidLimitOrCursorIsRejectedBeforeRepositoryAccess() {
        for (int limit : new int[] {0, -1, 101, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> service.list(OWNER, limit, null)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String cursor : new String[] {"%invalid", "", encode("not-a-number"), encode("-1"),
                encode("100001"), encode("2147483648")}) {
            assertThatThrownBy(() -> service.list(OWNER, 20, cursor)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void missingOwnedVersionFailsWithoutUnscopedLookupOrFabricatedCard() {
        PlanRecord record = records(1).getFirst();
        when(repository.list(OWNER, 21, 0)).thenReturn(List.of(record));
        when(repository.findVersion(OWNER, record.id(), record.version())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(OWNER, 20, null)).isInstanceOfSatisfying(AccountFailure.class, failure -> {
            assertThat(failure.status()).isEqualTo(404);
            assertThat(failure.code()).isEqualTo("VERSION_NOT_FOUND");
        });
        verify(repository).list(OWNER, 21, 0);
        verify(repository).findVersion(OWNER, record.id(), record.version());
        verifyNoMoreInteractions(repository);
    }

    private List<PlanRecord> records(int count) {
        List<PlanRecord> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new PlanRecord(new UUID(1, index + 1), index + 10, new UUID(2, index + 1),
                    "Saved goal " + index, mapper.readTree("{\"completedSessionIds\":[]}"),
                    "2026-09-01T00:00:00Z", "2026-09-02T12:30:00Z"));
        }
        return result;
    }

    private void stubVersion(PlanRecord record) {
        JsonNode snapshot = mapper.readTree("{\"config\":{\"days\":7,\"dailyHours\":1,\"topicIds\":[\"learn:core-java\"]},\"days\":[]}");
        PlanVersion version = new PlanVersion(record.version(), snapshot, mapper.createObjectNode(),
                mapper.createObjectNode(), mapper.createObjectNode());
        when(repository.findVersion(OWNER, record.id(), record.version())).thenReturn(Optional.of(version));
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
