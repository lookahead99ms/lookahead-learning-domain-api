package com.lookahead.learning.content.service;

import com.lookahead.learning.content.model.PlanRecord;
import com.lookahead.learning.content.model.PlanVersion;
import com.lookahead.learning.content.repository.PlanRepository;
import com.lookahead.learning.content.validator.SnapshotValidator;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.exception.AccountFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PlanNamingTest {
    final ObjectMapper mapper = new ObjectMapper();
    final PlanRepository repository = mock(PlanRepository.class);
    final SnapshotValidator validator = mock(SnapshotValidator.class);
    final PlanService service = new PlanService(repository, mapper, validator, new PlanJson(mapper));
    final UUID owner = UUID.randomUUID(), id = UUID.randomUUID(), version = UUID.randomUUID();
    PlanRecord record(String name, long revision) {
        return new PlanRecord(id, revision, version, "Unchanged goal", mapper.readTree("{\"notes\":{\"x\":\"Keep\"}}"),
            "2026-09-01T00:00:00Z", "2026-09-29T00:00:00Z", name, 3);
    }
    @Test void renameOnlyUpdatesNameAndRevision() {
        var snapshot = mapper.readTree("{\"days\":[],\"config\":{\"days\":90,\"dailyHours\":5}}");
        when(repository.findPlan(owner,id,true)).thenReturn(Optional.of(record("Old",7)));
        when(repository.findPlan(owner,id,false)).thenReturn(Optional.of(record("Completely custom",8)));
        when(repository.findVersion(owner,id,version)).thenReturn(Optional.of(new PlanVersion(version,snapshot,mapper.createObjectNode(),mapper.createObjectNode(),mapper.createObjectNode())));
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
        assertThat(PlanService.defaultPlanName(3,snapshot)).matches("Study plan #3_\\d{8}_5X90");
    }
}
