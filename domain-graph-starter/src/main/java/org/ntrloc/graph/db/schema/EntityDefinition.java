package org.ntrloc.graph.db.schema;

import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

public class EntityDefinition extends SchemaDefinition implements DefinitionWithPropertyGroups {

    private Set<PropertyDefinition> properties;

    private Set<PropertyGroupDefinition> propertyGroupDefinitions;

    public Set<PropertyDefinition> getProperties() {
        return properties;
    }

    public void setProperties(Set<PropertyDefinition> properties) {
        this.properties = properties;
    }

    public Set<PropertyGroupDefinition> getPropertyGroups() {
        return propertyGroupDefinitions;
    }

    public void setPropertyGroups(Set<PropertyGroupDefinition> propertyGroupDefinitions) {
        this.propertyGroupDefinitions = propertyGroupDefinitions;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", EntityDefinition.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .add("properties=" + properties)
                .add("propertyGroups=" + propertyGroupDefinitions)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityDefinition schema)) return false;
        return Objects.equals(properties, schema.properties) && Objects.equals(propertyGroupDefinitions, schema.propertyGroupDefinitions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(properties, propertyGroupDefinitions);
    }
}
