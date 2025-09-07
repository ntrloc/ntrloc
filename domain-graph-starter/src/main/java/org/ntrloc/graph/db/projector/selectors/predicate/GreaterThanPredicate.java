package org.ntrloc.graph.db.projector.selectors.predicate;

public class GreaterThanPredicate implements UnaryPredicate {

    Object value;

    public static GreaterThanPredicate of(Object value) {
        GreaterThanPredicate predicate = new GreaterThanPredicate();
        predicate.value = value;
        return predicate;
    }

    @Override
    public Object getValue() {
        return value;
    }

}
