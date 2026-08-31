package org.ntrloc.graph.db.projection;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PredicateEvaluatorTest {

    private static final Map<String, Object> PROPS = Map.of("status", "APPROVED", "priority", 5, "title", "Kafka");
    private static final Map<String, String> STATES = Map.of("Lifecycle", "InProgress");

    private static boolean eval(Predicate p) {
        return PredicateEvaluator.evaluate(p, PROPS, STATES);
    }

    @Test
    void propertyExistence() {
        assertThat(eval(new PropertyExistencePredicate("status"))).isTrue();
        assertThat(eval(new PropertyExistencePredicate("missing"))).isFalse();
    }

    @Test
    void propertyValueOperators() {
        assertThat(eval(new PropertyValuePredicate("status", Operator.EQUALS, "APPROVED"))).isTrue();
        assertThat(eval(new PropertyValuePredicate("status", Operator.NOT_EQUALS, "APPROVED"))).isFalse();
        assertThat(eval(new PropertyValuePredicate("priority", Operator.GREATER_THAN, "3"))).isTrue();
        assertThat(eval(new PropertyValuePredicate("priority", Operator.LESS_THAN_OR_EQUAL, "5"))).isTrue();
        assertThat(eval(new PropertyValuePredicate("priority", Operator.GREATER_THAN, "10"))).isFalse();
        assertThat(eval(new PropertyValuePredicate("title", Operator.LIKE, "%afk%"))).isTrue();
        assertThat(eval(new PropertyValuePredicate("missing", Operator.EQUALS, "x"))).isFalse();
    }

    @Test
    void stateValue() {
        assertThat(eval(new StateValuePredicate("Lifecycle", "InProgress"))).isTrue();
        assertThat(eval(new StateValuePredicate("Lifecycle", "Done"))).isFalse();
        assertThat(eval(new StateValuePredicate("Other", "InProgress"))).isFalse();
    }

    @Test
    void booleanCombinators() {
        Predicate approvedAndHighPriority = new AndPredicate(List.of(
                new PropertyValuePredicate("status", Operator.EQUALS, "APPROVED"),
                new PropertyValuePredicate("priority", Operator.GREATER_THAN_OR_EQUAL, "5")));
        assertThat(eval(approvedAndHighPriority)).isTrue();
        assertThat(eval(new NotPredicate(approvedAndHighPriority))).isFalse();
        assertThat(eval(new OrPredicate(List.of(
                new PropertyValuePredicate("status", Operator.EQUALS, "REJECTED"),
                new PropertyExistencePredicate("title"))))).isTrue();
    }

    @Test
    void roundTripsThroughStoredGuardJson() {
        var node = JsonMapper.builder().build().readTree(
                "{\"type\":\"PROPERTY_VALUE\",\"propertyName\":\"status\",\"operator\":\"EQUALS\",\"value\":\"APPROVED\"}");
        assertThat(PredicateEvaluator.evaluate(PredicateEvaluator.fromJson(node), PROPS, STATES)).isTrue();
    }
}
