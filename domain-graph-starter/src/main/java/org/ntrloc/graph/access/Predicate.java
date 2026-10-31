package org.ntrloc.graph.access;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// Split per base type only where the operator set carries real type-safety risk (string, list
// membership/presence, boolean, state machine, identity); collapsed to one fielded class wherever
// every operator is safely interchangeable (number, date, and any list/link reduced to a size, count,
// sum, avg, min, or max -- all of which are themselves just numbers or dates). See the marker
// decision pipeline design notes, "Conditions: reading facts" / "Object shape", for the full reasoning.
//
// Every predicate -- a leaf value test, a TotalityPredicate, or a CountedPredicate, no exceptions by
// kind -- carries its own `subject`, nullable, inherited from whatever contains it when absent (the
// enclosing Condition, or an enclosing TotalityPredicate/CountedPredicate's own effective subject).
// This is what lets two predicates nested in the same group address two different properties (see
// ConditionGroup's own comment), and what makes a TotalityPredicate/CountedPredicate's sub-predicates
// meaningfully composable instead of all being forced to share one subject declared only at the top.
//
// A predicate never has an output pin of its own -- only a Condition does (see Condition's own
// comment); TotalityPredicate/CountedPredicate nest other predicates directly, never by reference to a
// separately-pinned block.
//
// @JsonSubTypes lists every concrete leaf directly, skipping the intermediate sealed interfaces
// (StringPredicate, NumberListPredicate, etc.) -- those exist for Java-level type safety among
// PredicateEvaluator's own dispatch, but no field anywhere in this model is ever declared as one of
// them (always the bare Predicate), so there's nothing for a second JSON discriminator level to do.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
//
// EXISTS/NOT_EXISTS also read the names they replaced (STRING_EXISTS/STRING_NOT_EXISTS, NO_STATE), so a
// pipeline saved before still loads.
@JsonSubTypes({
        @JsonSubTypes.Type(value = ExistsPredicate.class, name = "EXISTS", names = "STRING_EXISTS"),
        @JsonSubTypes.Type(value = NotExistsPredicate.class, name = "NOT_EXISTS", names = {"STRING_NOT_EXISTS", "NO_STATE"}),
        @JsonSubTypes.Type(value = StringEqualsPredicate.class, name = "STRING_EQUALS"),
        @JsonSubTypes.Type(value = StringNotEqualsPredicate.class, name = "STRING_NOT_EQUALS"),
        @JsonSubTypes.Type(value = StringLikePredicate.class, name = "STRING_LIKE"),
        @JsonSubTypes.Type(value = StringStartsWithPredicate.class, name = "STRING_STARTS_WITH"),
        @JsonSubTypes.Type(value = NumberPredicate.class, name = "NUMBER"),
        @JsonSubTypes.Type(value = DatePredicate.class, name = "DATE"),
        @JsonSubTypes.Type(value = IsTruePredicate.class, name = "IS_TRUE"),
        @JsonSubTypes.Type(value = IsFalsePredicate.class, name = "IS_FALSE"),
        @JsonSubTypes.Type(value = StringListIncludesPredicate.class, name = "STRING_LIST_INCLUDES"),
        @JsonSubTypes.Type(value = StringListExcludesPredicate.class, name = "STRING_LIST_EXCLUDES"),
        @JsonSubTypes.Type(value = StringListIsEmptyPredicate.class, name = "STRING_LIST_IS_EMPTY"),
        @JsonSubTypes.Type(value = StringListIsNotEmptyPredicate.class, name = "STRING_LIST_IS_NOT_EMPTY"),
        @JsonSubTypes.Type(value = StringListSizePredicate.class, name = "STRING_LIST_SIZE"),
        @JsonSubTypes.Type(value = NumberListIsEmptyPredicate.class, name = "NUMBER_LIST_IS_EMPTY"),
        @JsonSubTypes.Type(value = NumberListIsNotEmptyPredicate.class, name = "NUMBER_LIST_IS_NOT_EMPTY"),
        @JsonSubTypes.Type(value = NumberListSizePredicate.class, name = "NUMBER_LIST_SIZE"),
        @JsonSubTypes.Type(value = NumberListSumPredicate.class, name = "NUMBER_LIST_SUM"),
        @JsonSubTypes.Type(value = NumberListAvgPredicate.class, name = "NUMBER_LIST_AVG"),
        @JsonSubTypes.Type(value = NumberListMinPredicate.class, name = "NUMBER_LIST_MIN"),
        @JsonSubTypes.Type(value = NumberListMaxPredicate.class, name = "NUMBER_LIST_MAX"),
        @JsonSubTypes.Type(value = DateListIsEmptyPredicate.class, name = "DATE_LIST_IS_EMPTY"),
        @JsonSubTypes.Type(value = DateListIsNotEmptyPredicate.class, name = "DATE_LIST_IS_NOT_EMPTY"),
        @JsonSubTypes.Type(value = DateListSizePredicate.class, name = "DATE_LIST_SIZE"),
        @JsonSubTypes.Type(value = DateListMinPredicate.class, name = "DATE_LIST_MIN"),
        @JsonSubTypes.Type(value = DateListMaxPredicate.class, name = "DATE_LIST_MAX"),
        @JsonSubTypes.Type(value = LinkIsPresentPredicate.class, name = "LINK_IS_PRESENT"),
        @JsonSubTypes.Type(value = LinkCountPredicate.class, name = "LINK_COUNT"),
        @JsonSubTypes.Type(value = StateEqualsPredicate.class, name = "STATE_EQUALS"),
        @JsonSubTypes.Type(value = EqualsSelfPredicate.class, name = "EQUALS_SELF"),
        @JsonSubTypes.Type(value = TotalityPredicate.class, name = "TOTALITY"),
        @JsonSubTypes.Type(value = CountedPredicate.class, name = "COUNTED")
})
public sealed interface Predicate
        permits StringPredicate, NumberPredicate, DatePredicate, BooleanPredicate,
        StringListPredicate, NumberListPredicate, DateListPredicate,
        LinkPredicate, StateMachinePredicate, IdentityPredicate, PresencePredicate,
        TotalityPredicate, CountedPredicate {

    Context subject();
}
