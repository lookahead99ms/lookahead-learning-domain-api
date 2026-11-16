package com.lookahead.domain.persistence.dynamodb;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class DynamoItemsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void keysAreTypedPrefixedAndLengthDelimited() {
        var id = UUID.fromString("0f3b4b1e-6a8b-4f7e-9c4f-2a0f8a1d4e22");
        assertThat(DynamoItems.account(id)).isEqualTo("ACCOUNT#0f3b4b1e-6a8b-4f7e-9c4f-2a0f8a1d4e22");
        assertThat(DynamoItems.plan(id, id)).startsWith("PLAN#").contains("#" + id + "#" + id);
        assertThat(DynamoItems.encode("ann#x")).isEqualTo("v1#5:ann#x");
        assertThat(DynamoItems.encode("日本")).isEqualTo("v1#6:日本");
        assertThat(DynamoItems.decode("v1#6:日本")).isEqualTo("日本");
        assertThat(DynamoItems.identity("https://issuer", "sub#1")).isEqualTo("IDENTITY#v1#14:https://issuer#v1#5:sub#1");
        for (String bad : new String[]{"", "a\nb", "x".repeat(1025)})
            assertThatThrownBy(() -> DynamoItems.encode(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DynamoItems.encode(null)).isInstanceOf(IllegalArgumentException.class);
        for (String bad : new String[]{"v2#1:a", "v1#2:a", "v1#x:a", "plain", "v1#1"})
            assertThatThrownBy(() -> DynamoItems.decode(bad)).hasMessage("Malformed key segment");
    }

    @Test void timeAndNumberKeysSortAsText() {
        var earlier = Instant.parse("2026-11-10T06:00:00.000000001Z");
        var later = Instant.parse("2026-11-10T06:00:00.000000002Z");
        assertThat(DynamoItems.time(earlier)).isEqualTo("2026-11-10T06:00:00.000000001Z").isLessThan(DynamoItems.time(later));
        assertThat(DynamoItems.time(Instant.parse("2026-11-10T06:00:00Z"))).isEqualTo("2026-11-10T06:00:00.000000000Z");
        var id = UUID.randomUUID();
        assertThat(DynamoItems.timeKey("EVENT", earlier, id)).isEqualTo("EVENT#2026-11-10T06:00:00.000000001Z#" + id);
        assertThat(DynamoItems.padded(42)).isEqualTo("0000000042");
        assertThat(DynamoItems.padded(9)).isLessThan(DynamoItems.padded(10));
        assertThatThrownBy(() -> DynamoItems.padded(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DynamoItems.padded(10_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DynamoItems.time(Instant.MAX)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void attributeReadersFailClosedOnMissingOrMalformedValues() {
        var id = UUID.randomUUID();
        var now = Instant.parse("2026-11-10T06:00:00Z");
        var attributes = new java.util.HashMap<String, AttributeValue>(Map.of("name", DynamoItems.s("Ann"), "revision", DynamoItems.n(3),
                "enabled", DynamoItems.bool(true), "roles", DynamoItems.strings(Set.of("b", "a")), "id", DynamoItems.s(id.toString()),
                "at", DynamoItems.s(now.toString()), "nothing", DynamoItems.nul()));
        attributes.put("absent", null);
        var item = DynamoItems.item("ACCOUNT#x", "META", 1, attributes);
        assertThat(item).containsKeys("pk", "sk", "schemaVersion", "name").doesNotContainKey("absent");
        DynamoItems.requireSchema(item, 1);
        assertThatThrownBy(() -> DynamoItems.requireSchema(item, 2)).hasMessage("Stored item has an unexpected schema version");
        assertThatThrownBy(() -> DynamoItems.requireSchema(Map.of(), 1)).isInstanceOf(IllegalStateException.class);
        assertThat(DynamoItems.string(item, "name")).isEqualTo("Ann");
        assertThat(DynamoItems.stringOrNull(item, "missing")).isNull();
        assertThat(DynamoItems.number(item, "revision")).isEqualTo(3);
        assertThat(DynamoItems.flag(item, "enabled")).isTrue();
        assertThat(DynamoItems.flag(item, "missing")).isFalse();
        assertThat(DynamoItems.stringSet(item, "roles")).containsExactlyInAnyOrder("a", "b");
        assertThat(item.get("roles").l()).extracting(AttributeValue::s).containsExactly("a", "b");
        assertThat(DynamoItems.stringSet(item, "missing")).isEmpty();
        assertThat(DynamoItems.uuid(item, "id")).isEqualTo(id);
        assertThat(DynamoItems.instant(item, "at")).isEqualTo(now);
        assertThat(DynamoItems.key("A", "B")).containsEntry("pk", DynamoItems.s("A")).containsEntry("sk", DynamoItems.s("B"));
        assertThatThrownBy(() -> DynamoItems.string(item, "revision")).hasMessage("Stored item lacks text attribute revision");
        assertThatThrownBy(() -> DynamoItems.number(item, "name")).hasMessage("Stored item lacks number attribute name");
        assertThatThrownBy(() -> DynamoItems.uuid(item, "name")).hasMessage("Stored item has a malformed UUID in name");
        assertThatThrownBy(() -> DynamoItems.instant(item, "name")).hasMessage("Stored item has a malformed time in name");
        assertThatThrownBy(() -> DynamoItems.number(Map.of("n", DynamoItems.s("01")), "n")).isInstanceOf(IllegalStateException.class);
    }

    @Test void jsonRoundTripsThroughMapsListsAndScalars() {
        var node = mapper.readTree("{\"goal\":\"Java\",\"days\":[1,2.5,{\"x\":null}],\"done\":false,\"n\":-7,\"big\":12345678901234567890,\"empty\":{},\"list\":[]}");
        var value = DynamoItems.json(node);
        assertThat(value.hasM()).isTrue();
        assertThat(value.m().get("goal").s()).isEqualTo("Java");
        assertThat(value.m().get("days").l().get(1).n()).isEqualTo("2.5");
        assertThat(value.m().get("days").l().get(2).m().get("x").nul()).isTrue();
        assertThat(value.m().get("done").bool()).isFalse();
        assertThat(DynamoItems.json(value, mapper)).isEqualTo(node);
        assertThat(DynamoItems.json(null)).isEqualTo(DynamoItems.nul());
        assertThat(DynamoItems.json((AttributeValue) null, mapper).isNull()).isTrue();
        assertThat(DynamoItems.json(DynamoItems.nul(), mapper).isNull()).isTrue();
        assertThatThrownBy(() -> DynamoItems.json(AttributeValue.builder().n("1 2").build(), mapper)).hasMessage("Stored number is malformed");
        assertThatThrownBy(() -> DynamoItems.json(AttributeValue.builder().build(), mapper)).hasMessage("Stored value has an unsupported type");
    }
}
