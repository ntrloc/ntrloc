package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PredicateEvaluatorTest {

    private final UUID selfId = UUID.randomUUID();

    @Test
    void stringEquals_matchesExactValueOnly() {
        Predicate p = new StringEqualsPredicate("OUT_OF_STOCK");

        assertThat(PredicateEvaluator.evaluate(p, "OUT_OF_STOCK", selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(p, "IN_STOCK", selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(p, null, selfId)).isFalse();
    }

    @Test
    void stringLike_supportsPercentAndUnderscoreWildcards() {
        Predicate p = new StringLikePredicate("Out_of%");

        assertThat(PredicateEvaluator.evaluate(p, "OutXof Stock", selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(p, "Outofstock", selfId)).isFalse();
    }

    @Test
    void stringExists_distinguishesPresentFromAbsent() {
        assertThat(PredicateEvaluator.evaluate(new StringExistsPredicate(), "x", selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new StringExistsPredicate(), null, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new StringNotExistsPredicate(), null, selfId)).isTrue();
    }

    @Test
    void numberPredicate_supportsEveryComparisonOperator() {
        assertThat(PredicateEvaluator.evaluate(new NumberPredicate(ComparisonOperator.GREATER_THAN, 5, null), 10, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberPredicate(ComparisonOperator.LESS_THAN, 5, null), 10, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new NumberPredicate(ComparisonOperator.BETWEEN, 1, 20), 10, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberPredicate(ComparisonOperator.BETWEEN, 11, 20), 10, selfId)).isFalse();
    }

    @Test
    void datePredicate_beforeAfterAndBetween() {
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);
        Instant tomorrow = now.plus(1, ChronoUnit.DAYS);

        assertThat(PredicateEvaluator.evaluate(new DatePredicate(DateComparisonOperator.BEFORE, now, null), yesterday, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new DatePredicate(DateComparisonOperator.AFTER, now, null), yesterday, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new DatePredicate(DateComparisonOperator.BETWEEN, yesterday, tomorrow), now, selfId)).isTrue();
    }

    @Test
    void booleanPredicate_isTrueAndIsFalse() {
        assertThat(PredicateEvaluator.evaluate(new IsTruePredicate(), Boolean.TRUE, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new IsFalsePredicate(), Boolean.FALSE, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new IsTruePredicate(), null, selfId)).isFalse();
    }

    @Test
    void stringListPredicates_membershipAndSize() {
        List<String> genres = List.of("Fiction", "Reference");

        assertThat(PredicateEvaluator.evaluate(new StringListIncludesPredicate("Fiction"), genres, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new StringListExcludesPredicate("Fiction"), genres, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new StringListSizePredicate(ComparisonOperator.EQUALS, 2, 0), genres, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new StringListIsEmptyPredicate(), List.of(), selfId)).isTrue();
    }

    @Test
    void numberListPredicates_aggregates() {
        List<Number> counts = List.of(1, 2, 3, 4);

        assertThat(PredicateEvaluator.evaluate(new NumberListSumPredicate(ComparisonOperator.EQUALS, 10, null), counts, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberListAvgPredicate(ComparisonOperator.EQUALS, 2.5, null), counts, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberListMinPredicate(ComparisonOperator.EQUALS, 1, null), counts, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberListMaxPredicate(ComparisonOperator.EQUALS, 4, null), counts, selfId)).isTrue();
    }

    @Test
    void dateListPredicates_minAndMaxReuseDatePredicateSemantics() {
        Instant early = Instant.parse("2020-01-01T00:00:00Z");
        Instant late = Instant.parse("2025-01-01T00:00:00Z");

        assertThat(PredicateEvaluator.evaluate(new DateListMinPredicate(DateComparisonOperator.EQUALS, early, null), List.of(early, late), selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new DateListMaxPredicate(DateComparisonOperator.EQUALS, late, null), List.of(early, late), selfId)).isTrue();
    }

    @Test
    void linkPredicates_presenceAndCount() {
        assertThat(PredicateEvaluator.evaluate(new LinkIsPresentPredicate(), 1, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new LinkIsPresentPredicate(), 0, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new LinkCountPredicate(ComparisonOperator.GREATER_THAN, 2, 0), 3, selfId)).isTrue();
    }

    @Test
    void stateMachinePredicates_equalsAndNoState() {
        UUID stateId = UUID.randomUUID();

        assertThat(PredicateEvaluator.evaluate(new StateEqualsPredicate(stateId), stateId, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new StateEqualsPredicate(stateId), UUID.randomUUID(), selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new NoStatePredicate(), null, selfId)).isTrue();
    }

    @Test
    void equalsSelfPredicate_comparesTheResolvedEntityAgainstTheRoot() {
        assertThat(PredicateEvaluator.evaluate(new EqualsSelfPredicate(), selfId, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new EqualsSelfPredicate(), UUID.randomUUID(), selfId)).isFalse();
    }
}
