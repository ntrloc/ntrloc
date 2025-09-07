package org.ntrloc.graph.db.projector.selectors.predicate;

public class LessThanPredicate implements UnaryPredicate {

    Object value;

    public static LessThanPredicate of(Object value) {
        LessThanPredicate predicate = new LessThanPredicate();
        predicate.value = value;
        return predicate;
    }

    @Override
    public Object getValue() {
        return value;
    }

}
