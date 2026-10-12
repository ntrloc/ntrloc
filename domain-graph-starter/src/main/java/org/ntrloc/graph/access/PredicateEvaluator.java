package org.ntrloc.graph.access;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

// Pure value-testing: given a Predicate and the value ContextResolver resolved for it, returns
// whether it holds. Knows nothing about how the value was resolved (own property, a traversed
// link, a list aggregate, a state machine's current state) -- that's ContextResolver's job, built
// up scenario by scenario as the evaluator grows. selfId is only read by IdentityPredicate, which
// compares an already-resolved entity id back against the pipeline's own root item.
public final class PredicateEvaluator {

    private PredicateEvaluator() {
    }

    @SuppressWarnings("unchecked")
    public static boolean evaluate(Predicate predicate, Object value, UUID selfId) {
        if (predicate instanceof StringPredicate p) return evaluateString(p, (String) value);
        if (predicate instanceof NumberPredicate p) return evaluateNumber(p, (Number) value);
        if (predicate instanceof DatePredicate p) return evaluateDate(p, (Instant) value);
        if (predicate instanceof BooleanPredicate p) return evaluateBoolean(p, (Boolean) value);
        if (predicate instanceof StringListPredicate p) return evaluateStringList(p, (List<String>) value);
        if (predicate instanceof NumberListPredicate p) return evaluateNumberList(p, (List<Number>) value);
        if (predicate instanceof DateListPredicate p) return evaluateDateList(p, (List<Instant>) value);
        if (predicate instanceof LinkPredicate p) return evaluateLink(p, (Integer) value);
        if (predicate instanceof StateMachinePredicate p) return evaluateStateMachine(p, (UUID) value);
        if (predicate instanceof IdentityPredicate p) return evaluateIdentity(p, (UUID) value, selfId);
        throw new IllegalArgumentException("Unsupported predicate: " + predicate);
    }

    private static boolean evaluateString(StringPredicate p, String value) {
        if (p instanceof StringEqualsPredicate e) return value != null && value.equals(e.value());
        if (p instanceof StringNotEqualsPredicate e) return value == null || !value.equals(e.value());
        if (p instanceof StringLikePredicate e) return value != null && likeMatch(value, e.pattern());
        if (p instanceof StringStartsWithPredicate e) return value != null && value.startsWith(e.prefix());
        if (p instanceof StringExistsPredicate) return value != null;
        if (p instanceof StringNotExistsPredicate) return value == null;
        throw new IllegalArgumentException("Unsupported StringPredicate: " + p);
    }

    // '%' = any run of characters, '_' = exactly one -- the same two wildcards SQL LIKE uses, since
    // that's the vocabulary an admin authoring these is most likely to already know.
    private static boolean likeMatch(String value, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return value.matches(regex.toString());
    }

    private static boolean evaluateNumber(NumberPredicate p, Number value) {
        return compareNumber(p.operator(), value, p.value(), p.valueTo());
    }

    private static boolean compareNumber(ComparisonOperator op, Number actual, Number value, Number valueTo) {
        double a = actual.doubleValue();
        return switch (op) {
            case EQUALS -> a == value.doubleValue();
            case NOT_EQUALS -> a != value.doubleValue();
            case GREATER_THAN -> a > value.doubleValue();
            case LESS_THAN -> a < value.doubleValue();
            case GREATER_THAN_OR_EQUAL -> a >= value.doubleValue();
            case LESS_THAN_OR_EQUAL -> a <= value.doubleValue();
            case BETWEEN -> a >= value.doubleValue() && a <= valueTo.doubleValue();
        };
    }

    private static boolean evaluateDate(DatePredicate p, Instant actual) {
        return switch (p.operator()) {
            case EQUALS -> actual.equals(p.value());
            case NOT_EQUALS -> !actual.equals(p.value());
            case BEFORE -> actual.isBefore(p.value());
            case AFTER -> actual.isAfter(p.value());
            case BETWEEN -> !actual.isBefore(p.value()) && !actual.isAfter(p.valueTo());
        };
    }

    private static boolean evaluateBoolean(BooleanPredicate p, Boolean value) {
        boolean actual = Boolean.TRUE.equals(value);
        if (p instanceof IsTruePredicate) return actual;
        if (p instanceof IsFalsePredicate) return !actual;
        throw new IllegalArgumentException("Unsupported BooleanPredicate: " + p);
    }

    private static boolean evaluateStringList(StringListPredicate p, List<String> value) {
        List<String> v = value == null ? List.of() : value;
        if (p instanceof StringListIncludesPredicate e) return v.contains(e.value());
        if (p instanceof StringListExcludesPredicate e) return !v.contains(e.value());
        if (p instanceof StringListIsEmptyPredicate) return v.isEmpty();
        if (p instanceof StringListIsNotEmptyPredicate) return !v.isEmpty();
        if (p instanceof StringListSizePredicate e) return compareNumber(e.operator(), v.size(), e.value(), e.valueTo());
        throw new IllegalArgumentException("Unsupported StringListPredicate: " + p);
    }

    private static boolean evaluateNumberList(NumberListPredicate p, List<Number> value) {
        List<Number> v = value == null ? List.of() : value;
        if (p instanceof NumberListIsEmptyPredicate) return v.isEmpty();
        if (p instanceof NumberListIsNotEmptyPredicate) return !v.isEmpty();
        if (p instanceof NumberListSizePredicate e) return compareNumber(e.operator(), v.size(), e.value(), e.valueTo());
        if (p instanceof NumberListSumPredicate e) return compareNumber(e.operator(), sum(v), e.value(), e.valueTo());
        if (p instanceof NumberListAvgPredicate e) return compareNumber(e.operator(), avg(v), e.value(), e.valueTo());
        if (p instanceof NumberListMinPredicate e) return compareNumber(e.operator(), min(v), e.value(), e.valueTo());
        if (p instanceof NumberListMaxPredicate e) return compareNumber(e.operator(), max(v), e.value(), e.valueTo());
        throw new IllegalArgumentException("Unsupported NumberListPredicate: " + p);
    }

    private static double sum(List<Number> v) {
        double s = 0;
        for (Number n : v) s += n.doubleValue();
        return s;
    }

    private static double avg(List<Number> v) {
        return v.isEmpty() ? 0 : sum(v) / v.size();
    }

    private static double min(List<Number> v) {
        double m = Double.POSITIVE_INFINITY;
        for (Number n : v) m = Math.min(m, n.doubleValue());
        return m;
    }

    private static double max(List<Number> v) {
        double m = Double.NEGATIVE_INFINITY;
        for (Number n : v) m = Math.max(m, n.doubleValue());
        return m;
    }

    private static boolean evaluateDateList(DateListPredicate p, List<Instant> value) {
        List<Instant> v = value == null ? List.of() : value;
        if (p instanceof DateListIsEmptyPredicate) return v.isEmpty();
        if (p instanceof DateListIsNotEmptyPredicate) return !v.isEmpty();
        if (p instanceof DateListSizePredicate e) return compareNumber(e.operator(), v.size(), e.value(), e.valueTo());
        if (p instanceof DateListMinPredicate e) {
            // subject is irrelevant here -- evaluateDate never reads it, this is just reusing the
            // operator/value/valueTo comparison logic against an already-reduced Instant.
            return evaluateDate(new DatePredicate(null, e.operator(), e.value(), e.valueTo()), minInstant(v));
        }
        if (p instanceof DateListMaxPredicate e) {
            return evaluateDate(new DatePredicate(null, e.operator(), e.value(), e.valueTo()), maxInstant(v));
        }
        throw new IllegalArgumentException("Unsupported DateListPredicate: " + p);
    }

    private static Instant minInstant(List<Instant> v) {
        return v.stream().min(Instant::compareTo).orElse(Instant.EPOCH);
    }

    private static Instant maxInstant(List<Instant> v) {
        return v.stream().max(Instant::compareTo).orElse(Instant.EPOCH);
    }

    private static boolean evaluateLink(LinkPredicate p, Integer value) {
        int count = value == null ? 0 : value;
        if (p instanceof LinkIsPresentPredicate) return count > 0;
        if (p instanceof LinkCountPredicate e) return compareNumber(e.operator(), count, e.value(), e.valueTo());
        throw new IllegalArgumentException("Unsupported LinkPredicate: " + p);
    }

    private static boolean evaluateStateMachine(StateMachinePredicate p, UUID currentStateId) {
        if (p instanceof StateEqualsPredicate e) return e.stateId().equals(currentStateId);
        if (p instanceof NoStatePredicate) return currentStateId == null;
        throw new IllegalArgumentException("Unsupported StateMachinePredicate: " + p);
    }

    private static boolean evaluateIdentity(IdentityPredicate p, UUID resolvedId, UUID selfId) {
        if (p instanceof EqualsSelfPredicate) return resolvedId != null && resolvedId.equals(selfId);
        throw new IllegalArgumentException("Unsupported IdentityPredicate: " + p);
    }
}
