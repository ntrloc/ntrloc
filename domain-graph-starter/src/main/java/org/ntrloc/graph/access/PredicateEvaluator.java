package org.ntrloc.graph.access;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
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
        return switch (predicate) {
            case PresencePredicate p -> evaluatePresence(p, value);
            case StringPredicate p -> evaluateString(p, (String) value);
            case NumberPredicate p -> evaluateNumber(p, (Number) value);
            case DatePredicate p -> evaluateDate(p, toInstant(value));
            case BooleanPredicate p -> evaluateBoolean(p, (Boolean) value);
            case StringListPredicate p -> evaluateStringList(p, (List<String>) value);
            case NumberListPredicate p -> evaluateNumberList(p, (List<Number>) value);
            case DateListPredicate p -> evaluateDateList(p, value == null ? null : ((List<Object>) value).stream().map(PredicateEvaluator::toInstant).toList());
            case LinkPredicate p -> evaluateLink(p, (Integer) value);
            case StateMachinePredicate p -> evaluateStateMachine(p, (UUID) value);
            case IdentityPredicate p -> evaluateIdentity(p, (UUID) value, selfId);
            // Quantified over many instances, so never resolved to one value: SignalEvaluator
            // evaluates these itself, applying this method to each sub-predicate.
            case TotalityPredicate _, CountedPredicate _ ->
                    throw new IllegalArgumentException("Evaluated by SignalEvaluator, not against a single value: " + predicate);
        };
    }

    // An empty list has no value, same as an unset property.
    private static boolean evaluatePresence(PresencePredicate p, Object value) {
        boolean exists = value != null && !(value instanceof Collection<?> c && c.isEmpty());
        return switch (p) {
            case ExistsPredicate _ -> exists;
            case NotExistsPredicate _ -> !exists;
        };
    }

    // A date property is stored as ISO text: a date ("2020-01-20", the start of that day, UTC) or a
    // date-time, with or without an offset (UTC when without).
    static Instant toInstant(Object value) {
        if (value == null || value instanceof Instant) return (Instant) value;
        String text = value.toString();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException _) {
            // not a date-time with an offset
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException _) {
            // not a date-time without one
        }
        return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static boolean evaluateString(StringPredicate p, String value) {
        return switch (p) {
            case StringEqualsPredicate e -> value != null && value.equals(e.value());
            case StringNotEqualsPredicate e -> value == null || !value.equals(e.value());
            case StringLikePredicate e -> value != null && likeMatch(value, e.pattern());
            case StringStartsWithPredicate e -> value != null && value.startsWith(e.prefix());
        };
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

    // With no value, only "does not equal" holds -- the same as a string's.
    private static boolean evaluateNumber(NumberPredicate p, Number value) {
        if (value == null) return p.operator() == ComparisonOperator.NOT_EQUALS;
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
        if (actual == null) return p.operator() == DateComparisonOperator.NOT_EQUALS;
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
        return switch (p) {
            case IsTruePredicate _ -> actual;
            case IsFalsePredicate _ -> !actual;
        };
    }

    private static boolean evaluateStringList(StringListPredicate p, List<String> value) {
        List<String> v = value == null ? List.of() : value;
        return switch (p) {
            case StringListIncludesPredicate e -> v.contains(e.value());
            case StringListExcludesPredicate e -> !v.contains(e.value());
            case StringListIsEmptyPredicate _ -> v.isEmpty();
            case StringListIsNotEmptyPredicate _ -> !v.isEmpty();
            case StringListSizePredicate e -> compareNumber(e.operator(), v.size(), e.value(), e.valueTo());
        };
    }

    private static boolean evaluateNumberList(NumberListPredicate p, List<Number> value) {
        List<Number> v = value == null ? List.of() : value;
        return switch (p) {
            case NumberListIsEmptyPredicate _ -> v.isEmpty();
            case NumberListIsNotEmptyPredicate _ -> !v.isEmpty();
            case NumberListSizePredicate e -> compareNumber(e.operator(), v.size(), e.value(), e.valueTo());
            case NumberListSumPredicate e -> compareNumber(e.operator(), sum(v), e.value(), e.valueTo());
            case NumberListAvgPredicate e -> !v.isEmpty() && compareNumber(e.operator(), avg(v), e.value(), e.valueTo());
            case NumberListMinPredicate e -> !v.isEmpty() && compareNumber(e.operator(), min(v), e.value(), e.valueTo());
            case NumberListMaxPredicate e -> !v.isEmpty() && compareNumber(e.operator(), max(v), e.value(), e.valueTo());
        };
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
        // For Min/Max, subject is irrelevant -- evaluateDate never reads it, this is just reusing the
        // operator/value/valueTo comparison logic against an already-reduced Instant.
        return switch (p) {
            case DateListIsEmptyPredicate _ -> v.isEmpty();
            case DateListIsNotEmptyPredicate _ -> !v.isEmpty();
            case DateListSizePredicate e -> compareNumber(e.operator(), v.size(), e.value(), e.valueTo());
            case DateListMinPredicate e -> !v.isEmpty() && evaluateDate(new DatePredicate(null, e.operator(), e.value(), e.valueTo()), minInstant(v));
            case DateListMaxPredicate e -> !v.isEmpty() && evaluateDate(new DatePredicate(null, e.operator(), e.value(), e.valueTo()), maxInstant(v));
        };
    }

    private static Instant minInstant(List<Instant> v) {
        return v.stream().min(Instant::compareTo).orElse(Instant.EPOCH);
    }

    private static Instant maxInstant(List<Instant> v) {
        return v.stream().max(Instant::compareTo).orElse(Instant.EPOCH);
    }

    private static boolean evaluateLink(LinkPredicate p, Integer value) {
        int count = value == null ? 0 : value;
        return switch (p) {
            case LinkIsPresentPredicate _ -> count > 0;
            case LinkCountPredicate e -> compareNumber(e.operator(), count, e.value(), e.valueTo());
        };
    }

    private static boolean evaluateStateMachine(StateMachinePredicate p, UUID currentStateId) {
        return switch (p) {
            case StateEqualsPredicate e -> e.stateId().equals(currentStateId);
        };
    }

    private static boolean evaluateIdentity(IdentityPredicate p, UUID resolvedId, UUID selfId) {
        return switch (p) {
            case EqualsSelfPredicate _ -> resolvedId != null && resolvedId.equals(selfId);
        };
    }
}
