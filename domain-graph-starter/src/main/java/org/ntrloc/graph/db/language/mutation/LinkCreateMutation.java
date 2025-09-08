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

public class LinkCreateMutation extends LinkMutation {

    private String relationshipType;
    private ItemReference sourceReference;
    private ItemReference targetReference;
    private Map<String, Property> properties = new HashMap<>();

    public LinkCreateMutation(String relationshipType) {
        this.relationshipType = relationshipType;
    }

    public String getRelationshipType() {
        return relationshipType;
    }

    public LinkCreateMutation source(ItemReference source) {
        this.sourceReference = source;
        return this;
    }

    public ItemReference getSource() {
        return sourceReference;
    }

    public LinkCreateMutation target(ItemReference target) {
        this.targetReference = target;
        return this;
    }

    public ItemReference getTarget() {
        return targetReference;
    }

    public LinkCreateMutation stringProperty(String name, String value) {
        properties.put(name, new StringProperty(name, value));
        return this;
    }

    public LinkCreateMutation stringListProperty(String name, List<String> values) {
        properties.put(name, new StringListProperty(name, values));
        return this;
    }

    public LinkCreateMutation intProperty(String name, Integer value) {
        properties.put(name, new IntProperty(name, value));
        return this;
    }

    public LinkCreateMutation intListProperty(String name, List<Integer> values) {
        properties.put(name, new IntListProperty(name, values));
        return this;
    }

    public LinkCreateMutation doubleProperty(String name, double value) {
        properties.put(name, new DoubleProperty(name, value));
        return this;
    }

    public LinkCreateMutation doubleListProperty(String name, List<Double> values) {
        properties.put(name, new DoubleListProperty(name, values));
        return this;
    }

    public LinkCreateMutation booleanProperty(String name, boolean value) {
        properties.put(name, new BooleanProperty(name, value));
        return this;
    }

    public LinkCreateMutation booleanListProperty(String name, List<Boolean> values) {
        properties.put(name, new BooleanListProperty(name, values));
        return this;
    }

    public LinkCreateMutation dateProperty(String name, Date value) {
        properties.put(name, new DateProperty(name, value));
        return this;
    }

    public LinkCreateMutation dateListProperty(String name, List<Date> values) {
        properties.put(name, new DateListProperty(name, values));
        return this;
    }

    public LinkCreateMutation binaryReferenceProperty(String name, Long nodeId) {
        properties.put(name, new BinaryReferenceProperty(name, nodeId));
        return this;
    }

    public Set<Property> getProperties() {
        return new HashSet<>(properties.values());
    }

}
