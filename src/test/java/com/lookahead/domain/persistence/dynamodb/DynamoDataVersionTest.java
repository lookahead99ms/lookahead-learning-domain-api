package com.lookahead.domain.persistence.dynamodb;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoDataVersionTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNamesTest.names("dev");
    private final DynamoDataVersionReader reader = new DynamoDataVersionReader(client, tables);

    static Map<String, AttributeValue> head(String version) {
        return Map.of("pk", s("DATA_VERSION#DOMAIN"), "sk", s("CURRENT"), "ledgerFormatVersion", n("1"), "version", n(version));
    }
    static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    static AttributeValue n(String value) { return AttributeValue.builder().n(value).build(); }

    @Test void releaseRequirementIsImmutableAndCannotBeEmptyOrAmbiguous() {
        var names = new ArrayList<>(List.of("base-tables", "plan-index"));
        var requirement = new DynamoDataRequirement(names);
        names.clear();
        assertThat(requirement.minimumVersion()).isEqualTo(2);
        assertThatThrownBy(() -> requirement.stepNames().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatNullPointerException().isThrownBy(() -> new DynamoDataRequirement(null));
        for (var invalid : List.of(List.<String>of(), List.of("a", "a"), List.of("A"), List.of("a\nsecret"),
                List.of("a-"), List.of("a".repeat(81)), Collections.nCopies(10_001, "a"))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new DynamoDataRequirement(invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new DynamoDataRequirement(Arrays.asList((String)null)));
    }

    @Test void missingStepDiagnosticsAreExactBoundedAndNeverShownForAnAheadVersion() {
        var requirement = new DynamoDataRequirement(List.of("base-tables", "plan-index", "quota-receipts"));
        assertThat(requirement.behindMessage(1)).isEqualTo("the data is at 1; this release needs 3 (missing 2 plan-index, 3 quota-receipts); run the migration before deploying");
        for (long invalid : new long[]{-1, 3, Long.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> requirement.behindMessage(invalid));
        }
        var many = new DynamoDataRequirement(IntStream.rangeClosed(1, 30).mapToObj(i -> "step-" + i).toList());
        assertThat(many.behindMessage(0)).contains("8 step-8, and 22 more").doesNotContain("9 step-9");
    }

    @Test void absentHeadIsZeroAndForwardCompatibleVersionsUseOneStrongProjectedRead() {
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            assertThat(request.tableName()).isEqualTo(tables.protectedOwnerIdentityTableName());
            assertThat(request.key()).isEqualTo(DynamoDataVersionReader.CURRENT_KEY);
            assertThat(request.consistentRead()).isTrue();
            assertThat(request.projectionExpression()).isEqualTo("pk, sk, #version, ledgerFormatVersion");
            assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout()).contains(java.time.Duration.ofSeconds(2));
            return GetItemResponse.builder().build();
        });
        assertThat(reader.currentVersion()).isZero();
        for (String version : List.of("1", "7", Long.toString(Long.MAX_VALUE))) {
            doReturn(GetItemResponse.builder().item(head(version)).build()).when(client).getItem(any(GetItemRequest.class));
            assertThat(reader.currentVersion()).isEqualTo(Long.parseLong(version));
        }
        verify(client, times(4)).getItem(any(GetItemRequest.class));
        verifyNoMoreInteractions(client);
    }

    @Test void corruptHeadNeverBecomesZeroAndNeverEchoesContents() {
        var corrupt = new ArrayList<Map<String, AttributeValue>>();
        for (String version : List.of("0", "-1", "1.5", "1e3", "01", "9223372036854775808", "1".repeat(20), "private-secret")) corrupt.add(head(version));
        for (String field : List.of("pk", "sk", "version", "ledgerFormatVersion")) {
            var missing = new HashMap<>(head("1")); missing.remove(field); corrupt.add(missing);
            var wrong = new HashMap<>(head("1")); wrong.put(field, s("private-secret")); corrupt.add(wrong);
        }
        var futureFormat = new HashMap<>(head("1")); futureFormat.put("ledgerFormatVersion", n("2")); corrupt.add(futureFormat);
        for (var item : corrupt) {
            when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());
            assertThatThrownBy(reader::currentVersion).isInstanceOf(DynamoDataVersionReader.InvalidDataVersion.class)
                    .hasMessage("Invalid data-version watermark");
        }
        when(client.getItem(any(GetItemRequest.class))).thenReturn(null);
        assertThatThrownBy(reader::currentVersion).isInstanceOf(DynamoDataVersionReader.InvalidDataVersion.class);
    }

    @Test void dependenciesAreRequiredAndSdkFailuresPropagateWithoutWrites() {
        assertThatNullPointerException().isThrownBy(() -> new DynamoDataVersionReader(null, tables));
        assertThatNullPointerException().isThrownBy(() -> new DynamoDataVersionReader(client, null));
        var error = DynamoDbException.builder().message("private-secret").build();
        when(client.getItem(any(GetItemRequest.class))).thenThrow(error);
        assertThatThrownBy(reader::currentVersion).isSameAs(error);
        verify(client).getItem(any(GetItemRequest.class));
        verifyNoMoreInteractions(client);
    }
}
