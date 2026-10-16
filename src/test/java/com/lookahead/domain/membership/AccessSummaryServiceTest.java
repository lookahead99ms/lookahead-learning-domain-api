package com.lookahead.domain.membership;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AccessSummaryServiceTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void freePurchasePromoAndManualGrantLabelsAreDerivedAndDeterministic() {
        assertThat(AccessSummaryService.summarize(List.of(),Set.of()).label()).isEqualTo("Free");
        var regular=json.readTree("{\"name\":\"Learn\",\"group\":\"regular\"}");
        var promo=json.readTree("{\"name\":\"Annual Promo\",\"group\":\"promo\"}");
        assertThat(AccessSummaryService.summarize(List.of(regular),Set.of()).label()).isEqualTo("Regular · Learn");
        assertThat(AccessSummaryService.summarize(List.of(promo,regular,regular),Set.of()).labels())
                .containsExactly("Promo · Annual Promo","Regular · Learn");
        assertThat(AccessSummaryService.summarize(List.of(),Set.of("grow:api-design","learn:core-java")).label())
                .isEqualTo("Granted · grow:api-design, learn:core-java");
    }
    @Test void coursePassListsOnlyItsPurchasedCourseIds() {
        var plan=json.readTree("""
            {"name":"Course Pass","group":"regular","scope":{"kind":"courses","courseIds":["learn:core-java","grow:api-design"]}}
            """);
        assertThat(AccessSummaryService.summarize(List.of(plan),Set.of()).label())
                .isEqualTo("Regular · Course Pass · grow:api-design, learn:core-java");
    }
}
