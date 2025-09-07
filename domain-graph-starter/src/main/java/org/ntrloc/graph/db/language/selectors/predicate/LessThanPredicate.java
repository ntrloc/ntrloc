package org.ntrloc.graph.db.language.selectors.predicate;

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
