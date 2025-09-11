package org.ntrloc.graph.db.language.selectors;

public class IdSelector implements ItemSelector, LinkSelector {

    public enum Type {
        LOCAL,
        GLOBAL
    }

    private String id;
    private Type type;

    public IdSelector(String id, Type type) {
        this.id = id;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

}
