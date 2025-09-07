package org.ntrloc.graph.db.language.selectors;

public class LabelSelector implements ItemSelector, LinkSelector {

    private String label;

    public LabelSelector(String label) {
        this.label = label;
    }

    public static LabelSelector on(String label) {
        return new LabelSelector(label);
    }

    public String getLabel() {
        return label;
    }

}
