package org.ntrloc.graph.db.partition.authorization;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A pipeline editor names the blocks and conditions it adds by refIds -- new-block-N, new-condition-N
// -- until they're saved, as the browser doesn't make ids. One refId can turn up anywhere in a save:
// a block's or condition's id, a gate's pins, a route's ends, the layout's position keys and the
// edge keys built from them ("new-block-1->...", "group-new-condition-3"). This gives each refId one
// new id and puts it in every place, values and keys alike, before the save is read as a Pipeline.
final class PipelineRefIds {

    private static final Pattern REF_ID = Pattern.compile("new-(?:block|condition)-\\d+");

    private final Map<String, String> ids = new HashMap<>();

    private PipelineRefIds() {
    }

    static JsonNode resolve(JsonNode node) {
        return new PipelineRefIds().resolved(node);
    }

    private JsonNode resolved(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode result = object.objectNode();
            for (var entry : object.properties()) {
                result.set(replaced(entry.getKey()), resolved(entry.getValue()));
            }
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = array.arrayNode();
            array.forEach(element -> result.add(resolved(element)));
            return result;
        }
        if (node != null && node.isString()) {
            String text = node.asString();
            String replaced = replaced(text);
            return replaced.equals(text) ? node : StringNode.valueOf(replaced);
        }
        return node;
    }

    private String replaced(String text) {
        Matcher matcher = REF_ID.matcher(text);
        if (!matcher.find()) return text;
        return matcher.replaceAll(match -> ids.computeIfAbsent(match.group(), _ -> UUID.randomUUID().toString()));
    }
}
