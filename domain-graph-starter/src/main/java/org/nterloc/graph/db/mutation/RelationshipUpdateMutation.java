package org.nterloc.graph.db.mutation;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RelationshipUpdateMutation extends RelationshipMutation {

    private String id;
    private EntityReference sourceReference;
    private EntityReference targetReference;
    private Map<String, Property> properties = new HashMap<>();

    public RelationshipUpdateMutation id(String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

    public RelationshipUpdateMutation source(EntityReference source) {
        this.sourceReference = source;
        return this;
    }

    public EntityReference getSource() {
        return sourceReference;
    }

    public RelationshipUpdateMutation target(EntityReference target) {
        this.targetReference = target;
        return this;
    }

    public EntityReference getTarget() {
        return targetReference;
    }

    public RelationshipUpdateMutation stringProperty(String name, String value) {
        properties.put(name, new StringProperty(name, value));
        return this;
    }

    public RelationshipUpdateMutation stringListProperty(String name, List<String> values) {
        properties.put(name, new StringListProperty(name, values));
        return this;
    }

    public RelationshipUpdateMutation intProperty(String name, Integer value) {
        properties.put(name, new IntProperty(name, value));
        return this;
    }

    public RelationshipUpdateMutation intListProperty(String name, List<Integer> values) {
        properties.put(name, new IntListProperty(name, values));
        return this;
    }

    public RelationshipUpdateMutation doubleProperty(String name, double value) {
        properties.put(name, new DoubleProperty(name, value));
        return this;
    }

    public RelationshipUpdateMutation doubleListProperty(String name, List<Double> values) {
        properties.put(name, new DoubleListProperty(name, values));
        return this;
    }

    public RelationshipUpdateMutation booleanProperty(String name, boolean value) {
        properties.put(name, new BooleanProperty(name, value));
        return this;
    }

    public RelationshipUpdateMutation booleanListProperty(String name, List<Boolean> values) {
        properties.put(name, new BooleanListProperty(name, values));
        return this;
    }

    public RelationshipUpdateMutation dateProperty(String name, Date value) {
        properties.put(name, new DateProperty(name, value));
        return this;
    }

    public RelationshipUpdateMutation dateListProperty(String name, List<Date> values) {
        properties.put(name, new DateListProperty(name, values));
        return this;
    }

    public RelationshipUpdateMutation binaryReferenceProperty(String name, Long nodeId) {
        properties.put(name, new BinaryReferenceProperty(name, nodeId));
        return this;
    }

    public Set<Property> getProperties() {
        return new HashSet<>(properties.values());
    }

}
