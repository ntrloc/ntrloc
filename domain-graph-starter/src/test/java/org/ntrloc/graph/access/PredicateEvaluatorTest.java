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
        Predicate p = new StringEqualsPredicate(null, "OUT_OF_STOCK");

        assertThat(PredicateEvaluator.evaluate(p, "OUT_OF_STOCK", selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(p, "IN_STOCK", selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(p, null, selfId)).isFalse();
    }

    @Test
    void stringLike_supportsPercentAndUnderscoreWildcards() {
        Predicate p = new StringLikePredicate(null, "Out_of%");

        assertThat(PredicateEvaluator.evaluate(p, "OutXof Stock", selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(p, "Outofstock", selfId)).isFalse();
    }

    @Test
    void stringExists_distinguishesPresentFromAbsent() {
        assertThat(PredicateEvaluator.evaluate(new StringExistsPredicate(null), "x", selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new StringExistsPredicate(null), null, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new StringNotExistsPredicate(null), null, selfId)).isTrue();
    }

    @Test
    void numberPredicate_supportsEveryComparisonOperator() {
        assertThat(PredicateEvaluator.evaluate(new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 5, null), 10, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberPredicate(null, ComparisonOperator.LESS_THAN, 5, null), 10, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new NumberPredicate(null, ComparisonOperator.BETWEEN, 1, 20), 10, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberPredicate(null, ComparisonOperator.BETWEEN, 11, 20), 10, selfId)).isFalse();
    }

    @Test
    void datePredicate_beforeAfterAndBetween() {
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);
        Instant tomorrow = now.plus(1, ChronoUnit.DAYS);

        assertThat(PredicateEvaluator.evaluate(new DatePredicate(null, DateComparisonOperator.BEFORE, now, null), yesterday, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new DatePredicate(null, DateComparisonOperator.AFTER, now, null), yesterday, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new DatePredicate(null, DateComparisonOperator.BETWEEN, yesterday, tomorrow), now, selfId)).isTrue();
    }

    @Test
    void booleanPredicate_isTrueAndIsFalse() {
        assertThat(PredicateEvaluator.evaluate(new IsTruePredicate(null), Boolean.TRUE, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new IsFalsePredicate(null), Boolean.FALSE, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new IsTruePredicate(null), null, selfId)).isFalse();
    }

    @Test
    void stringListPredicates_membershipAndSize() {
        List<String> genres = List.of("Fiction", "Reference");

        assertThat(PredicateEvaluator.evaluate(new StringListIncludesPredicate(null, "Fiction"), genres, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new StringListExcludesPredicate(null, "Fiction"), genres, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new StringListSizePredicate(null, ComparisonOperator.EQUALS, 2, 0), genres, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new StringListIsEmptyPredicate(null), List.of(), selfId)).isTrue();
    }

    @Test
    void numberListPredicates_aggregates() {
        List<Number> counts = List.of(1, 2, 3, 4);

        assertThat(PredicateEvaluator.evaluate(new NumberListSumPredicate(null, ComparisonOperator.EQUALS, 10, null), counts, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberListAvgPredicate(null, ComparisonOperator.EQUALS, 2.5, null), counts, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberListMinPredicate(null, ComparisonOperator.EQUALS, 1, null), counts, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new NumberListMaxPredicate(null, ComparisonOperator.EQUALS, 4, null), counts, selfId)).isTrue();
    }

    @Test
    void dateListPredicates_minAndMaxReuseDatePredicateSemantics() {
        Instant early = Instant.parse("2020-01-01T00:00:00Z");
        Instant late = Instant.parse("2025-01-01T00:00:00Z");

        assertThat(PredicateEvaluator.evaluate(new DateListMinPredicate(null, DateComparisonOperator.EQUALS, early, null), List.of(early, late), selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new DateListMaxPredicate(null, DateComparisonOperator.EQUALS, late, null), List.of(early, late), selfId)).isTrue();
    }

    @Test
    void linkPredicates_presenceAndCount() {
        assertThat(PredicateEvaluator.evaluate(new LinkIsPresentPredicate(null), 1, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new LinkIsPresentPredicate(null), 0, selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new LinkCountPredicate(null, ComparisonOperator.GREATER_THAN, 2, 0), 3, selfId)).isTrue();
    }

    @Test
    void stateMachinePredicates_equalsAndNoState() {
        UUID stateId = UUID.randomUUID();

        assertThat(PredicateEvaluator.evaluate(new StateEqualsPredicate(null, stateId), stateId, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new StateEqualsPredicate(null, stateId), UUID.randomUUID(), selfId)).isFalse();
        assertThat(PredicateEvaluator.evaluate(new NoStatePredicate(null), null, selfId)).isTrue();
    }

    @Test
    void equalsSelfPredicate_comparesTheResolvedEntityAgainstTheRoot() {
        assertThat(PredicateEvaluator.evaluate(new EqualsSelfPredicate(null), selfId, selfId)).isTrue();
        assertThat(PredicateEvaluator.evaluate(new EqualsSelfPredicate(null), UUID.randomUUID(), selfId)).isFalse();
    }
}
