package org.ntrloc.graph.db.language.selectors.predicate;

public class NotEqualsPredicate implements UnaryPredicate {

    Object value;

    public static NotEqualsPredicate of(Object value) {
        NotEqualsPredicate predicate = new NotEqualsPredicate();
        predicate.value = value;
        return predicate;
    }

    @Override
    public Object getValue() {
        return value;
    }

}
