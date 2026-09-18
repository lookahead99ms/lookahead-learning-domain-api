package com.lookahead.learning.content.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stable JSON representation and digest used by snapshot and retry receipts. */
@Component
public final class PlanJson {
    private final ObjectMapper mapper;

    public PlanJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            var names = new TreeSet<String>();
            node.properties().forEach(e -> names.add(e.getKey()));
            for (String name : names) sorted.set(name, parse(canonical(node.path(name))));
            return json(sorted);
        }
        if (node.isArray()) {
            var array = mapper.createArrayNode();
            node.forEach(n -> array.add(parse(canonical(n))));
            return json(array);
        }
        return json(node);
    }

    public String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String json(JsonNode value) {
        return mapper.writeValueAsString(value);
    }

    public JsonNode parse(String value) {
        return mapper.readTree(value);
    }
}
