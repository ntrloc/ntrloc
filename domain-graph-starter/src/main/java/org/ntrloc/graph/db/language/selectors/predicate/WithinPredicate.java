package org.ntrloc.graph.db.language.selectors.predicate;

import java.util.List;

public class WithinPredicate implements UnaryPredicate<List<Object>> {

    List<Object> value;

    public static WithinPredicate on(List<Object> value) {
        WithinPredicate predicate = new WithinPredicate();
        predicate.value = value;
        return predicate;
    }

    @Override
    public List<Object> getValue() {
        return value;
    }

}
