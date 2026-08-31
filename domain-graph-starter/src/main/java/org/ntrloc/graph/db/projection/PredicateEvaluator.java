package org.ntrloc.graph.db.projection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

// In-memory evaluation of a stored predicate against a single already-loaded item -- used to check
// a transition's guard at execution time (RegisterPartitionManager.translatePredicate is the other
// consumer, and only produces SQL). Guards are property/state predicates only (Predicate permits no
// link leaf), so this needs just the item's name-keyed property values plus its current state per
// machine.
public final class PredicateEvaluator {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private PredicateEvaluator() {
    }

    /** Deserialize a stored guard JSON node into a Predicate (the sealed interface carries its own @JsonSubTypes). */
    public static Predicate fromJson(JsonNode guard) {
        return MAPPER.treeToValue(guard, Predicate.class);
    }

    /**
     * @param propsByName             the item's projected property values, keyed by property name
     * @param currentStateByMachineName state-machine name -> the item's current state name in it
     */
    public static boolean evaluate(Predicate predicate, Map<String, Object> propsByName,
                                    Map<String, String> currentStateByMachineName) {
        if (predicate instanceof AndPredicate p) {
            return p.predicates().stream().allMatch(c -> evaluate(c, propsByName, currentStateByMachineName));
        }
        if (predicate instanceof OrPredicate p) {
            return p.predicates().stream().anyMatch(c -> evaluate(c, propsByName, currentStateByMachineName));
        }
        if (predicate instanceof NotPredicate p) {
            return !evaluate(p.predicate(), propsByName, currentStateByMachineName);
        }
        if (predicate instanceof PropertyExistencePredicate p) {
            return propsByName.get(p.propertyName()) != null;
        }
        if (predicate instanceof StateValuePredicate p) {
            return p.stateName().equals(currentStateByMachineName.get(p.stateMachineName()));
        }
        if (predicate instanceof PropertyValuePredicate p) {
            Object actual = propsByName.get(p.propertyName());
            return actual != null && compare(actual, p.operator(), p.value());
        }
        throw new IllegalArgumentException("Unsupported predicate: " + predicate.getClass().getSimpleName());
    }

    private static boolean compare(Object actual, Operator op, String expected) {
        String actualStr = String.valueOf(actual);
        if (op == Operator.EQUALS) return actualStr.equals(expected);
        if (op == Operator.NOT_EQUALS) return !actualStr.equals(expected);
        if (op == Operator.LIKE) return actualStr.toLowerCase().contains(unwrapLike(expected).toLowerCase());

        Double a = parseNumber(actualStr);
        Double b = parseNumber(expected);
        int cmp = (a != null && b != null) ? Double.compare(a, b) : actualStr.compareTo(expected);
        switch (op) {
            case LESS_THAN: return cmp < 0;
            case LESS_THAN_OR_EQUAL: return cmp <= 0;
            case GREATER_THAN: return cmp > 0;
            case GREATER_THAN_OR_EQUAL: return cmp >= 0;
            default: throw new IllegalArgumentException("Unsupported operator: " + op);
        }
    }

    private static Double parseNumber(String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // A guard-author's LIKE pattern is compared here as a plain case-insensitive substring match;
    // strip the SQL wildcards so "%term%" behaves as expected.
    private static String unwrapLike(String pattern) {
        String p = pattern;
        if (p.startsWith("%")) p = p.substring(1);
        if (p.endsWith("%")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
