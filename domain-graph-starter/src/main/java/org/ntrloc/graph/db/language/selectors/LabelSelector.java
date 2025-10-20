package org.ntrloc.graph.db.language.selectors;

import java.util.StringJoiner;

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

    @Override
    public String toString() {
        return new StringJoiner(", ", LabelSelector.class.getSimpleName() + "[", "]")
                .add("label='" + label + "'")
                .toString();
    }
}
