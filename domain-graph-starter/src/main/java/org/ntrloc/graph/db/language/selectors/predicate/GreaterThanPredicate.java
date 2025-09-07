package org.ntrloc.graph.db.language.selectors.predicate;

public class GreaterThanPredicate implements UnaryPredicate<Object> {

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
