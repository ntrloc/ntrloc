package org.ntrloc.graph.db.language.selectors.predicate;

public class EqualsPredicate implements UnaryPredicate<Object> {

    Object value;

    public static EqualsPredicate of(Object value) {
        EqualsPredicate predicate = new EqualsPredicate();
        predicate.value = value;
        return predicate;
    }

    @Override
    public Object getValue() {
        return value;
    }

}
