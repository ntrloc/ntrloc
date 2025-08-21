package org.ntrloc.graph.db.schema;

import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

public class PropertyGroupDefinition {

    private String name;

    private String description;

    private Set<PropertyDefinition> properties;

    public PropertyGroupDefinition() {
        // no-op
    }

    public PropertyGroupDefinition(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public PropertyGroupDefinition(String name, String description, Set<PropertyDefinition> properties) {
        this(name, description);
        this.properties = properties;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<PropertyDefinition> getProperties() {
        return properties;
    }

    public void setProperties(Set<PropertyDefinition> properties) {
        this.properties = properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PropertyGroupDefinition that)) return false;
        return Objects.equals(name, that.name) && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, properties);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", PropertyGroupDefinition.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .add("properties=" + properties)
                .toString();
    }
}
