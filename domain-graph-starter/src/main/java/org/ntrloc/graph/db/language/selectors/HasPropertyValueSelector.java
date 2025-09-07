package org.ntrloc.graph.db.language.selectors;

import org.ntrloc.graph.db.language.selectors.predicate.Predicate;

public class HasPropertyValueSelector implements ItemSelector, LinkSelector {

    private String name;
    private Predicate predicate;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Predicate getPredicate() {
        return predicate;
    }

    public void setPredicate(Predicate predicate) {
        this.predicate = predicate;
    }

    public static HasPropertyValueSelector of(String name, Predicate predicate) {
        HasPropertyValueSelector selector = new HasPropertyValueSelector();
        selector.setName(name);
        selector.setPredicate(predicate);
        return selector;
    }

}
