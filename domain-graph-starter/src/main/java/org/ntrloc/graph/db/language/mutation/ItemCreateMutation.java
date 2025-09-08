package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.BinaryReferenceProperty;
import org.ntrloc.graph.db.language.BooleanListProperty;
import org.ntrloc.graph.db.language.BooleanProperty;
import org.ntrloc.graph.db.language.DateListProperty;
import org.ntrloc.graph.db.language.DateProperty;
import org.ntrloc.graph.db.language.DoubleListProperty;
import org.ntrloc.graph.db.language.DoubleProperty;
import org.ntrloc.graph.db.language.IntListProperty;
import org.ntrloc.graph.db.language.IntProperty;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.StringListProperty;
import org.ntrloc.graph.db.language.StringProperty;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ItemCreateMutation extends ItemMutation {

    private String entityType;
    private Map<String, Property> properties = new HashMap<>();
    private String refId;

    public ItemCreateMutation entityType(String entityType) {
        this.entityType = entityType;
        return this;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityType() {
        return entityType;
    }

    public ItemCreateMutation refId(String refId) {
        this.refId = refId;
        return this;
    }

    public String getRefId() {
        return refId;
    }

    public void setProperties(List<? extends Property> properties) {
        this.properties = properties.stream().collect(Collectors.toMap(Property::getName, p -> p));
    }

    public ItemCreateMutation stringProperty(String name, String value) {
        properties.put(name, new StringProperty(name, value));
        return this;
    }

    public ItemCreateMutation stringListProperty(String name, List<String> values) {
        properties.put(name, new StringListProperty(name, values));
        return this;
    }

    public ItemCreateMutation intProperty(String name, Integer value) {
        properties.put(name, new IntProperty(name, value));
        return this;
    }

    public ItemCreateMutation intListProperty(String name, List<Integer> values) {
        properties.put(name, new IntListProperty(name, values));
        return this;
    }

    public ItemCreateMutation doubleProperty(String name, double value) {
        properties.put(name, new DoubleProperty(name, value));
        return this;
    }

    public ItemCreateMutation doubleListProperty(String name, List<Double> values) {
        properties.put(name, new DoubleListProperty(name, values));
        return this;
    }

    public ItemCreateMutation booleanProperty(String name, boolean value) {
        properties.put(name, new BooleanProperty(name, value));
        return this;
    }

    public ItemCreateMutation booleanListProperty(String name, List<Boolean> values) {
        properties.put(name, new BooleanListProperty(name, values));
        return this;
    }

    public ItemCreateMutation dateProperty(String name, Date value) {
        properties.put(name, new DateProperty(name, value));
        return this;
    }

    public ItemCreateMutation dateListProperty(String name, List<Date> values) {
        properties.put(name, new DateListProperty(name, values));
        return this;
    }

    public ItemCreateMutation binaryReferenceProperty(String name, Long nodeId) {
        properties.put(name, new BinaryReferenceProperty(name, nodeId));
        return this;
    }

    public Set<Property> getProperties() {
        return new HashSet<>(properties.values());
    }

}
