package org.ntrloc.graph.db.projector;

public class LabelSelector extends NodeSelector {

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
