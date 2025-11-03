package org.ntrloc.graph.db.schema;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public class ItemDefinition extends SchemaDefinition implements DefinitionWithPropertyGroups {

    private String name;
    private Set<PropertyDefinition> properties;
    private Map<String, PropertyDefinition> propertyDefinitionMap;

    private Set<PropertyGroupDefinition> propertyGroupDefinitions;

    public Set<PropertyDefinition> getProperties() {
        return properties;
    }

    public PropertyDefinition getPropertyDefinition(String name) {
        return propertyDefinitionMap.get(name);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setProperties(Set<PropertyDefinition> properties) {
        this.properties = properties;
        this.propertyDefinitionMap = properties.stream().collect(Collectors.toMap(PropertyDefinition::getName, def -> def));
    }

    public Set<PropertyGroupDefinition> getPropertyGroups() {
        return propertyGroupDefinitions;
    }

    public void setPropertyGroups(Set<PropertyGroupDefinition> propertyGroupDefinitions) {
        this.propertyGroupDefinitions = propertyGroupDefinitions;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", ItemDefinition.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .add("properties=" + properties)
                .add("propertyGroups=" + propertyGroupDefinitions)
                .add("uid='" + uid + "'")
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemDefinition schema)) return false;
        return Objects.equals(properties, schema.properties) && Objects.equals(propertyGroupDefinitions, schema.propertyGroupDefinitions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(properties, propertyGroupDefinitions);
    }
}
