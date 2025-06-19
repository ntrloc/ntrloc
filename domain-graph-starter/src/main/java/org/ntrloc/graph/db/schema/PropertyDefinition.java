package org.ntrloc.graph.db.schema;

import java.util.Objects;
import java.util.StringJoiner;

public class PropertyDefinition {

    String name;

    PropertyType type;

    String description;

    /**
     * Indicates that a modification to the property should result in the creation of a new
     * entity or relationship version.
     */
    boolean versionOnChange;

    public PropertyDefinition() {
        // no-arg
    }

    public PropertyDefinition(String name, PropertyType type, String description) {
        this(name, type, description, false);
    }

    public PropertyDefinition(String name, PropertyType type, String description, boolean versionOnChange) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.versionOnChange = versionOnChange;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PropertyType getType() {
        return type;
    }

    public void setType(PropertyType type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isVersionOnChange() {
        return versionOnChange;
    }

    public void setVersionOnChange(boolean versionOnChange) {
        this.versionOnChange = versionOnChange;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PropertyDefinition propertyDefinition)) return false;
        return Objects.equals(name, propertyDefinition.name) && type == propertyDefinition.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", PropertyDefinition.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .add("type=" + type)
                .toString();
    }
}
