package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Spot-checks the split-vs-collapsed shape, not every one of the ~40 leaf types individually: string
// operators stay split (one class per operator, real type-safety risk), while number/date/list-
// aggregate operators collapse to one fielded class with an operator (no such risk once you're
// comparing a plain number or date). See "Signals: reading facts" / "Object shape" in the design
// notes for the full reasoning.
class PredicateTest {

    @Test
    void stringOperatorsStaySplitIntoDistinctClasses() {
        Predicate startsWith = new StringStartsWithPredicate("draft");
        Predicate like = new StringLikePredicate("%draft%");

        // Deliberately distinct types, not one generic StringPredicate{operator, value} -- startsWith
        // spares an admin from writing wildcard syntax for the common prefix case.
        assertThat(startsWith).isInstanceOf(StringStartsWithPredicate.class);
        assertThat(like).isInstanceOf(StringLikePredicate.class);
        assertThat(startsWith).isNotInstanceOf(StringLikePredicate.class);
    }

    @Test
    void numberOperatorsCollapseToOneFieldedClass() {
        Predicate gt = new NumberPredicate(ComparisonOperator.GREATER_THAN, 100, null);
        Predicate between = new NumberPredicate(ComparisonOperator.BETWEEN, 1, 10);

        assertThat(gt).isInstanceOf(NumberPredicate.class);
        assertThat(between).isInstanceOf(NumberPredicate.class);
        assertThat(((NumberPredicate) between).valueTo()).isEqualTo(10);
    }

    @Test
    void datesUseBeforeAfterNotGenericOrdering() {
        // Deliberately a different operator enum from ComparisonOperator -- see DateComparisonOperator.
        DatePredicate before = new DatePredicate(DateComparisonOperator.BEFORE, java.time.Instant.EPOCH, null);
        assertThat(before.operator()).isEqualTo(DateComparisonOperator.BEFORE);
    }

    @Test
    void listAggregatesCollapseLikeNumberPredicateDespiteBeingOnAList() {
        // sum/avg/min/max all reduce to a plain number -- same "no type-safety risk left" reasoning
        // as NumberPredicate, so each is one class, not split per comparison operator.
        Predicate sum = new NumberListSumPredicate(ComparisonOperator.GREATER_THAN, 10000, null);
        Predicate size = new NumberListSizePredicate(ComparisonOperator.EQUALS, 3, 0);

        assertThat(sum).isInstanceOf(NumberListPredicate.class);
        assertThat(size).isInstanceOf(NumberListPredicate.class);
    }

    @Test
    void listMembershipStaysSplitFromListPresence() {
        Predicate includes = new StringListIncludesPredicate("NATURE");
        Predicate isEmpty = new StringListIsEmptyPredicate();

        assertThat(includes).isInstanceOf(StringListPredicate.class);
        assertThat(isEmpty).isInstanceOf(StringListPredicate.class);
        assertThat(includes).isNotInstanceOf(StringListIsEmptyPredicate.class);
    }

    @Test
    void equalsSelfIsTheIdentityComparand() {
        Predicate equalsSelf = new EqualsSelfPredicate();
        assertThat(equalsSelf).isInstanceOf(IdentityPredicate.class);
        assertThat(equalsSelf).isEqualTo(new EqualsSelfPredicate());
    }

    @Test
    void linkPredicatesCoverExistenceAndCardinality() {
        Predicate isPresent = new LinkIsPresentPredicate();
        Predicate count = new LinkCountPredicate(ComparisonOperator.GREATER_THAN, 0, 0);

        assertThat(isPresent).isInstanceOf(LinkPredicate.class);
        assertThat(count).isInstanceOf(LinkPredicate.class);
    }

    @Test
    void stateMachinePredicatesCoverCurrentStateAndNoState() {
        java.util.UUID approved = java.util.UUID.randomUUID();
        Predicate stateEquals = new StateEqualsPredicate(approved);
        Predicate noState = new NoStatePredicate();

        assertThat(stateEquals).isInstanceOf(StateMachinePredicate.class);
        assertThat(noState).isInstanceOf(StateMachinePredicate.class);
    }
}
