package org.ntrloc.graph.db.projector.selectors;

public class HasPropertySelector implements ItemSelector, LinkSelector {

    private String propertyName;

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

}
