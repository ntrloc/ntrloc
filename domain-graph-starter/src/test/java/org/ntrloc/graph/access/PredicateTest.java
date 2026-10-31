package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

// Spot-checks the split-vs-collapsed shape, not every one of the ~40 leaf types individually: string
// operators stay split (one class per operator, real type-safety risk), while number/date/list-
// aggregate operators collapse to one fielded class with an operator (no such risk once you're
// comparing a plain number or date). See "Conditions: reading facts" / "Object shape" in the design
// notes for the full reasoning.
class PredicateTest {

    @Test
    void stringOperatorsStaySplitIntoDistinctClasses() {
        Predicate startsWith = new StringStartsWithPredicate(null, "draft");
        Predicate like = new StringLikePredicate(null, "%draft%");

        // Deliberately distinct types, not one generic StringPredicate{operator, value} -- startsWith
        // spares an admin from writing wildcard syntax for the common prefix case.
        assertThat(startsWith).isInstanceOf(StringStartsWithPredicate.class);
        assertThat(like).isInstanceOf(StringLikePredicate.class);
        assertThat(startsWith).isNotInstanceOf(StringLikePredicate.class);
    }

    @Test
    void numberOperatorsCollapseToOneFieldedClass() {
        Predicate gt = new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 100, null);
        Predicate between = new NumberPredicate(null, ComparisonOperator.BETWEEN, 1, 10);

        assertThat(gt).isInstanceOf(NumberPredicate.class);
        assertThat(between).isInstanceOf(NumberPredicate.class);
        assertThat(((NumberPredicate) between).valueTo()).isEqualTo(10);
    }

    @Test
    void datesUseBeforeAfterNotGenericOrdering() {
        // Deliberately a different operator enum from ComparisonOperator -- see DateComparisonOperator.
        DatePredicate before = new DatePredicate(null, DateComparisonOperator.BEFORE, java.time.Instant.EPOCH, null);
        assertThat(before.operator()).isEqualTo(DateComparisonOperator.BEFORE);
    }

    @Test
    void listAggregatesCollapseLikeNumberPredicateDespiteBeingOnAList() {
        // sum/avg/min/max all reduce to a plain number -- same "no type-safety risk left" reasoning
        // as NumberPredicate, so each is one class, not split per comparison operator.
        Predicate sum = new NumberListSumPredicate(null, ComparisonOperator.GREATER_THAN, 10000, null);
        Predicate size = new NumberListSizePredicate(null, ComparisonOperator.EQUALS, 3, 0);

        assertThat(sum).isInstanceOf(NumberListPredicate.class);
        assertThat(size).isInstanceOf(NumberListPredicate.class);
    }

    @Test
    void listMembershipStaysSplitFromListPresence() {
        Predicate includes = new StringListIncludesPredicate(null, "NATURE");
        Predicate isEmpty = new StringListIsEmptyPredicate(null);

        assertThat(includes).isInstanceOf(StringListPredicate.class);
        assertThat(isEmpty).isInstanceOf(StringListPredicate.class);
        assertThat(includes).isNotInstanceOf(StringListIsEmptyPredicate.class);
    }

    @Test
    void equalsSelfIsTheIdentityComparand() {
        Predicate equalsSelf = new EqualsSelfPredicate(null);
        assertThat(equalsSelf).isInstanceOf(IdentityPredicate.class);
        assertThat(equalsSelf).isEqualTo(new EqualsSelfPredicate(null));
    }

    @Test
    void linkPredicatesCoverExistenceAndCardinality() {
        Predicate isPresent = new LinkIsPresentPredicate(null);
        Predicate count = new LinkCountPredicate(null, ComparisonOperator.GREATER_THAN, 0, 0);

        assertThat(isPresent).isInstanceOf(LinkPredicate.class);
        assertThat(count).isInstanceOf(LinkPredicate.class);
    }

    @Test
    void stateMachinePredicatesCoverCurrentState() {
        Predicate stateEquals = new StateEqualsPredicate(null, java.util.UUID.randomUUID());

        assertThat(stateEquals).isInstanceOf(StateMachinePredicate.class);
    }

    @Test
    void existsAndNotExists_alsoReadTheNamesTheyReplaced() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(mapper.readValue("{\"type\":\"STRING_EXISTS\"}", Predicate.class)).isInstanceOf(ExistsPredicate.class);
        assertThat(mapper.readValue("{\"type\":\"STRING_NOT_EXISTS\"}", Predicate.class)).isInstanceOf(NotExistsPredicate.class);
        assertThat(mapper.readValue("{\"type\":\"NO_STATE\"}", Predicate.class)).isInstanceOf(NotExistsPredicate.class);
        assertThat(mapper.writeValueAsString(new NotExistsPredicate(null))).contains("\"type\":\"NOT_EXISTS\"");
    }
}
