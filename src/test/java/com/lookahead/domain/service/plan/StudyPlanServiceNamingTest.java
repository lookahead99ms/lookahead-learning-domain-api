package com.lookahead.domain.service.plan;

import com.lookahead.domain.model.StudyPlanRecord;
import com.lookahead.domain.model.StudyPlanVersion;
import com.lookahead.domain.db.store.plan.StudyPlanParts;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.validator.StudyPlanSnapshotValidator;
import com.lookahead.domain.util.StudyPlanJson;
import com.lookahead.domain.exception.AccountFailure;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class StudyPlanServiceNamingTest {
    final ObjectMapper mapper = new ObjectMapper();
    final StudyPlanParts repository = mock(StudyPlanParts.class, withSettings().mockMaker(MockMakers.PROXY));
    final StudyPlanSnapshotValidator validator = mock(StudyPlanSnapshotValidator.class);
    final StudyPlanService service = new StudyPlanService(repository, repository, repository, mapper, validator, mock(AccountCatalogService.class), new StudyPlanJson(mapper), null);
    final UUID owner = UUID.randomUUID(), id = UUID.randomUUID(), version = UUID.randomUUID();
    StudyPlanRecord record(String name, long revision) {
        return new StudyPlanRecord(id, revision, version, "Unchanged goal", mapper.readTree("{\"notes\":{\"x\":\"Keep\"}}"),
            "2026-09-01T00:00:00Z", "2026-09-29T00:00:00Z", name, 3);
    }
    @Test void renameOnlyUpdatesNameAndRevision() {
        var snapshot = mapper.readTree("{\"days\":[],\"config\":{\"days\":90,\"dailyHours\":5}}");
        when(repository.findPlan(owner,id,true)).thenReturn(Optional.of(record("Old",7)));
        when(repository.findPlan(owner,id,false)).thenReturn(Optional.of(record("Completely custom",8)));
        when(repository.findVersion(owner,id,version)).thenReturn(Optional.of(new StudyPlanVersion(version,snapshot,mapper.createObjectNode(),mapper.createObjectNode(),mapper.createObjectNode())));
        var body=mapper.readTree("{\"expectedRevision\":7,\"name\":\" Completely custom \"}");
        var result=service.rename(owner,id,UUID.randomUUID(),body).data();
        assertThat(result.path("name").asText()).isEqualTo("Completely custom");
        assertThat(result.path("snapshot")).isEqualTo(snapshot);
        assertThat(result.path("progress")).isEqualTo(record("Old",7).progress());
        assertThat(result.path("goal").asText()).isEqualTo("Unchanged goal");
        assertThat(result.path("planNumber").asLong()).isEqualTo(3);
        verify(repository).rename(owner,id,"Completely custom",8);
        verify(repository,never()).activateVersion(any(),any(),any(),anyLong(),any());
        verify(repository,never()).updateProgress(any(),any(),any(),anyLong());
    }
    @Test void rejectsOtherOwnersStaleRevisionsAndInvalidNames() {
        when(repository.findPlan(owner,id,true)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.rename(owner,id,UUID.randomUUID(),mapper.readTree("{\"expectedRevision\":7,\"name\":\"Mine\"}"))).isInstanceOf(AccountFailure.class);
        when(repository.findPlan(owner,id,true)).thenReturn(Optional.of(record("Old",7)));
        assertThatThrownBy(()->service.rename(owner,id,UUID.randomUUID(),mapper.readTree("{\"expectedRevision\":6,\"name\":\"Mine\"}"))).isInstanceOf(AccountFailure.class);
        for(String name:List.of(" ", "x".repeat(161), "bad\nname")) {
            var body=mapper.createObjectNode().put("expectedRevision",7).put("name",name);
            assertThatThrownBy(()->service.rename(owner,id,UUID.randomUUID(),body)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(repository,never()).rename(any(),any(),any(),anyLong());
    }
    @Test void defaultUsesConfiguredHoursAndTotalStudyDays() {
        var snapshot=mapper.readTree("{\"config\":{\"dailyHours\":5.0,\"days\":90}}");
        assertThat(StudyPlanService.defaultPlanName(3,snapshot)).matches("Study plan #3_\\d{8}_5X90");
    }

    /**
     * DLV-1016: a replay receipt keeps the same three numbers in its {@code status} attribute as before the service
     * answered a typed outcome, and reads each one back as the outcome it was written for.
     */
    @Test void aReceiptKeepsTheSameNumberForEachOutcome() {
        Assertions.assertThat(Arrays.stream(StudyPlanService.Outcome.values()).map(StudyPlanService.Outcome::receiptCode).toList()).containsExactly(201, 200, 204);
        for (var outcome : StudyPlanService.Outcome.values())
            Assertions.assertThat(StudyPlanService.Outcome.ofReceiptCode(outcome.receiptCode())).isEqualTo(outcome);
        Assertions.assertThatThrownBy(() -> StudyPlanService.Outcome.ofReceiptCode(202))
                .isInstanceOf(IllegalStateException.class).hasMessage("Plan receipt with unknown status 202");
    }
}
