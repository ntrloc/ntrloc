package org.ntrloc.graph.db.projector.selectors;

public class HasPropertyValueSelector implements ItemSelector, LinkSelector {

    private String propertyName;
    private Object propertyValue;

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    public Object getPropertyValue() {
        return propertyValue;
    }

    public void setPropertyValue(Object propertyValue) {
        this.propertyValue = propertyValue;
    }

}
