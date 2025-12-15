package org.ntrloc.graph.db.language.selectors;

import java.util.StringJoiner;

public class IdSelector implements ItemSelector, LinkSelector {

    public enum Scope {
        LOCAL,
        GLOBAL
    }

    private String id;
    private Scope scope = Scope.GLOBAL;

    public IdSelector() {
        // no-op for Jackson
    }

    public IdSelector(String id) {
        this(id, Scope.GLOBAL);
    }

    public IdSelector(String id, Scope scope) {
        this.id = id;
        this.scope = scope;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", IdSelector.class.getSimpleName() + "[", "]")
                .add("id='" + id + "'")
                .add("type=" + scope)
                .toString();
    }

}
