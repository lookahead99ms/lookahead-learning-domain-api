package com.lookahead.domain.model;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TestAccountScenarioTest {
    @Test void rosterMatchesEveryApprovedMatrixRowAndExcludesTheOwner() {
        // DLV-971 (owner, 2026-11-06): learner test accounts only; no co-author or admin test account anywhere.
        assertThat(TestAccountRoster.entries()).hasSize(27);
        assertThat(TestAccountRoster.entries().stream().map(TestAccountRoster.Entry::username))
                .doesNotHaveDuplicates().doesNotContain("author01","author@lookahead.test");
        assertThat(TestAccountRoster.entries().stream().map(TestAccountRoster.Entry::username))
                .containsExactlyElementsOf(TestAccountScenario.all().stream().map(TestAccountScenario::username).toList())
                .noneMatch(name->name.startsWith("coauthor")||name.startsWith("admin"));
        assertThat(TestAccountRoster.entries()).allSatisfy(entry->assertThat(entry.template()).isEqualTo(entry.username()));
        assertThat(TestAccountRoster.isRoster(new TestAccountRoster.Entry("learner27").accountId())).isTrue();
        for(String retired:List.of("coauthor01","coauthor02","admin01","admin06"))
            assertThat(TestAccountRoster.isRoster(new TestAccountRoster.Entry(retired).accountId())).as(retired).isFalse();
        String[] purchases={"-","-","-","-","learn","learn","learn","grow","grow","grow","advance","advance","advance",
                "complete","complete","complete","advance","complete","complete","interview-sprint","interview-sprint",
                "interview-sprint","monthly-promo","annual-promo","interview-pass-promo","monthly-promo","advance"};
        String[] progress={"NONE","IN_PROGRESS","OVERDUE","NOT_STARTED","IN_PROGRESS","IN_PROGRESS","FINISHED","IN_PROGRESS",
                "OVERDUE","NONE","IN_PROGRESS","IN_PROGRESS","FINISHED","IN_PROGRESS","IN_PROGRESS","OVERDUE","IN_PROGRESS",
                "NOT_STARTED","IN_PROGRESS","IN_PROGRESS","OVERDUE","FINISHED","NOT_STARTED","IN_PROGRESS","IN_PROGRESS","FINISHED","IN_PROGRESS"};
        for(int n=1;n<=27;n++) {
            var row=TestAccountScenario.of(n);
            assertThat(Objects.toString(row.membership(),"-")).as("membership row %s",n).isEqualTo(purchases[n-1]);
            assertThat(row.progress().name()).as("progress row %s",n).isEqualTo(progress[n-1]);
            var entry=TestAccountRoster.entries().get(n-1);
            assertThat(entry.email()).isEqualTo(row.username()+"@lookahead.test");
            assertThat(entry.firstName()).isEqualTo("Learner%02d".formatted(n));
            assertThat(entry.accountId()).isEqualTo(UUID.nameUUIDFromBytes(("lookahead-local-test:"+row.username()).getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(TestAccountScenario.of(12).scheduled()).isEqualTo("grow");
        assertThat(TestAccountScenario.of(15).scheduled()).isEqualTo("advance");
        assertThat(TestAccountScenario.of(19).staffUpgrade()).isTrue();
        assertThat(TestAccountScenario.of(17).upgradedFrom()).isEqualTo("learn");
        assertThat(TestAccountScenario.of(18).upgradedFrom()).isEqualTo("grow");
        assertThat(TestAccountScenario.of(26).extraMembership()).isEqualTo("interview-pass-promo");
        assertThat(TestAccountScenario.of(27).extraMembership()).isEqualTo("interview-sprint");
        assertThatThrownBy(()->TestAccountScenario.of(28)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void datesRemainRelativeAndRespectCalendarTerms() {
        Instant now=Instant.parse("2028-03-01T12:00:00Z");
        for(var row:TestAccountScenario.all()) {
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
        assertThat(TestAccountScenario.of(26).dates(now,"months",1).startsAt()).isBefore(now.minus(Duration.ofDays(30)));
        assertThat(TestAccountScenario.of(22).dates(now,"days",100).endsAt()).isEqualTo(now.minus(Duration.ofDays(10)));
        assertThatThrownBy(()->TestAccountScenario.of(1).dates(now,"years",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->TestAccountScenario.of(6).dates(now,"invalid",1)).isInstanceOf(IllegalArgumentException.class);
    }
}
