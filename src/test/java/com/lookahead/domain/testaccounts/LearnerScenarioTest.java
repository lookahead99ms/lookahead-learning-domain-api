package com.lookahead.domain.testaccounts;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LearnerScenarioTest {
    @Test void rosterMatchesEveryApprovedMatrixRowAndExcludesTheOwner() {
        assertThat(TestAccountRoster.entries()).hasSize(35);
        assertThat(TestAccountRoster.entries().stream().map(TestAccountRoster.Entry::username))
                .doesNotHaveDuplicates().doesNotContain("author01","author@lookahead.test");
        assertThat(TestAccountRoster.entries().stream().filter(e->!e.staff()).map(TestAccountRoster.Entry::username))
                .containsExactlyElementsOf(LearnerScenario.all().stream().map(LearnerScenario::username).toList());
        String[] purchases={"-","-","-","-","learn","learn","learn","grow","grow","grow","advance","advance","advance",
                "complete","complete","complete","advance","complete","complete","interview-sprint","interview-sprint",
                "interview-sprint","monthly-promo","annual-promo","interview-pass-promo","monthly-promo","advance"};
        String[] progress={"NONE","IN_PROGRESS","OVERDUE","NOT_STARTED","IN_PROGRESS","IN_PROGRESS","FINISHED","IN_PROGRESS",
                "OVERDUE","NONE","IN_PROGRESS","IN_PROGRESS","FINISHED","IN_PROGRESS","IN_PROGRESS","OVERDUE","IN_PROGRESS",
                "NOT_STARTED","IN_PROGRESS","IN_PROGRESS","OVERDUE","FINISHED","NOT_STARTED","IN_PROGRESS","IN_PROGRESS","FINISHED","IN_PROGRESS"};
        for(int n=1;n<=27;n++) {
            var row=LearnerScenario.of(n);
            assertThat(Objects.toString(row.membership(),"-")).as("membership row %s",n).isEqualTo(purchases[n-1]);
            assertThat(row.progress().name()).as("progress row %s",n).isEqualTo(progress[n-1]);
            var entry=TestAccountRoster.entries().get(n-1);
            assertThat(entry.email()).isEqualTo(row.username()+"@lookahead.test");
            assertThat(entry.firstName()).isEqualTo("Learner%02d".formatted(n));
            assertThat(entry.accountId()).isEqualTo(UUID.nameUUIDFromBytes(("lookahead-local-test:"+row.username()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        assertThat(LearnerScenario.of(12).scheduled()).isEqualTo("grow");
        assertThat(LearnerScenario.of(15).scheduled()).isEqualTo("advance");
        assertThat(LearnerScenario.of(19).staffUpgrade()).isTrue();
        assertThat(LearnerScenario.of(17).upgradedFrom()).isEqualTo("learn");
        assertThat(LearnerScenario.of(18).upgradedFrom()).isEqualTo("grow");
        assertThat(LearnerScenario.of(26).extraMembership()).isEqualTo("interview-pass-promo");
        assertThat(LearnerScenario.of(27).extraMembership()).isEqualTo("interview-sprint");
        assertThatThrownBy(()->LearnerScenario.of(28)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void datesRemainRelativeAndRespectCalendarTerms() {
        Instant now=Instant.parse("2028-03-01T12:00:00Z");
        for(var row:LearnerScenario.all()) {
            if(row.membership()==null)continue;
            var dates=row.dates(now,"years",1);
            assertThat(dates.endsAt()).isAfter(dates.startsAt());
            switch(row.term()) {
                case ACTIVE -> {assertThat(dates.startsAt()).isBefore(now);assertThat(dates.endsAt()).isAfter(now);}
                case ENDING -> assertThat(dates.endsAt()).isEqualTo(now.plus(Duration.ofDays(4)));
                case EXPIRED -> assertThat(dates.endsAt()).isEqualTo(now.minus(Duration.ofDays(10)));
                default -> fail("Unexpected purchased term");
            }
        }
        assertThat(LearnerScenario.of(26).dates(now,"months",1).startsAt()).isBefore(now.minus(Duration.ofDays(30)));
        assertThat(LearnerScenario.of(22).dates(now,"days",100).endsAt()).isEqualTo(now.minus(Duration.ofDays(10)));
        assertThatThrownBy(()->LearnerScenario.of(1).dates(now,"years",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LearnerScenario.of(6).dates(now,"invalid",1)).isInstanceOf(IllegalArgumentException.class);
    }
}
