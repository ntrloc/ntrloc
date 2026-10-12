package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// Pins down the arity table from "Conditions: reading facts" / "Object shape": Condition is exactly
// one predicate, ConditionGroup needs 2+ conditions (there's nothing to group at 1), TotalityPredicate
// needs 2+ sub-predicates (there's nothing to combine at 1), CountedPredicate wraps exactly one
// sub-predicate and its subject must actually iterate something. Every Condition carries its own pin
// id; ConditionGroup has none of its own, only its Conditions do; TotalityPredicate/CountedPredicate
// never have a pin at all, since they're predicates nested directly, not separately-pinned blocks.
class SignalBlockTest {

    @Test
    void conditionHoldsExactlyOnePredicate() {
        Condition condition = new Condition(UUID.randomUUID(), new Self(), new StringEqualsPredicate(null, "CLUBS"));
        assertThat(condition.predicate()).isInstanceOf(StringEqualsPredicate.class);
    }

    @Test
    void conditionGroupRejectsFewerThanTwoConditions() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ConditionGroup(new Self(), List.of(
                        new Condition(UUID.randomUUID(), null, new StringEqualsPredicate(null, "CLUBS")))));
    }

    @Test
    void conditionGroupAllowsTwoOrMoreConditionsEachWithItsOwnPin() {
        Condition nature = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE"));
        Condition weddings = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "WEDDINGS"));

        ConditionGroup group = new ConditionGroup(new Self(), List.of(nature, weddings));

        assertThat(group.conditions()).hasSize(2);
        assertThat(group.conditions().get(0).id()).isNotEqualTo(group.conditions().get(1).id());
    }

    @Test
    void conditionGroupIsASignalBlockButNotACondition() {
        // ConditionGroup has no pin of its own -- only its contained Conditions do -- so it can never
        // stand in wherever "a condition" (a single pin) is expected. Enforced by the type system:
        // ConditionGroup simply doesn't implement Condition, so this wouldn't even compile if attempted.
        ConditionGroup group = new ConditionGroup(new Self(), List.of(
                new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE")),
                new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "WEDDINGS"))));
        assertThat(group).isInstanceOf(SignalBlock.class);
        assertThat(group).isNotInstanceOf(Condition.class);
    }

    @Test
    void totalityPredicateRejectsFewerThanTwoSubPredicates() {
        Predicate onlyOne = new IsTruePredicate(null);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TotalityPredicate(null, Quantity.ALL, List.of(onlyOne)));
    }

    @Test
    void totalityPredicateCombinesDistinctSubPredicatesInOrder() {
        Predicate hireDate = new IsTruePredicate(null);
        Predicate firstName = new StringExistsPredicate(null);

        TotalityPredicate totality = new TotalityPredicate(null, Quantity.ALL, List.of(hireDate, firstName));

        assertThat(totality.subPredicates()).containsExactly(hireDate, firstName);
    }

    @Test
    void countedPredicateRejectsASubjectWithNoEachStep() {
        Context notIterated = new StepToLink(UUID.randomUUID(), false, new Self());
        Predicate wrapped = new IsTruePredicate(null);

        assertThatIllegalArgumentException().isThrownBy(() ->
                new CountedPredicate(notIterated, wrapped,
                        new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null)));
    }

    @Test
    void countedPredicateAcceptsASubjectWithAnEachStepSomewhereInTheChain() {
        // each is on the second hop, not the first -- containsEachStep must recurse, not just check
        // the outermost step.
        Context photosThenEachPortfolio = new StepToItem(UUID.randomUUID(), false,
                new StepToItem(UUID.randomUUID(), true, new Self()));
        Predicate approved = new StateEqualsPredicate(null, UUID.randomUUID());

        CountedPredicate counted = new CountedPredicate(photosThenEachPortfolio, approved,
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));

        assertThat(counted.subPredicate()).isEqualTo(approved);
    }

    @Test
    void countedPredicateCanWrapATotalityPredicate() {
        // The HAS ACTIVE PORTFOLIO PHOTO shape: counted predicate wrapping a totality predicate,
        // nesting arbitrarily deep -- see the worked example in "Marker actions" / "Conditions: reading
        // facts".
        Context eachPortfolio = new StepToItem(UUID.randomUUID(), true, new Self());
        Predicate createDate = new DatePredicate(null, DateComparisonOperator.AFTER, java.time.Instant.EPOCH, null);
        Predicate approvedStatus = new StateEqualsPredicate(null, UUID.randomUUID());
        TotalityPredicate both = new TotalityPredicate(null, Quantity.ALL, List.of(createDate, approvedStatus));

        CountedPredicate counted = new CountedPredicate(eachPortfolio, both,
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));

        assertThat(counted.subPredicate()).isInstanceOf(TotalityPredicate.class);
    }
}
