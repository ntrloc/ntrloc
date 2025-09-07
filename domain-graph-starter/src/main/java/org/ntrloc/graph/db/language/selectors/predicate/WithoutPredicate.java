package org.ntrloc.graph.db.language.selectors.predicate;

import java.util.List;

public class WithoutPredicate implements UnaryPredicate<List<Object>> {

    List<Object> value;

    public static WithoutPredicate on(List<Object> value) {
        WithoutPredicate predicate = new WithoutPredicate();
        predicate.value = value;
        return predicate;
    }

    @Override
    public List<Object> getValue() {
        return value;
    }

}
