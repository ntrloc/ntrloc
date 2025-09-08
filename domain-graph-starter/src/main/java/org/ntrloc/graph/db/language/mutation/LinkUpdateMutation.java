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

public class LinkUpdateMutation extends LinkMutation {

    private String id;
    private ItemReference sourceReference;
    private ItemReference targetReference;
    private Map<String, Property> properties = new HashMap<>();

    public LinkUpdateMutation id(String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

    public LinkUpdateMutation source(ItemReference source) {
        this.sourceReference = source;
        return this;
    }

    public ItemReference getSource() {
        return sourceReference;
    }

    public LinkUpdateMutation target(ItemReference target) {
        this.targetReference = target;
        return this;
    }

    public ItemReference getTarget() {
        return targetReference;
    }

    public LinkUpdateMutation stringProperty(String name, String value) {
        properties.put(name, new StringProperty(name, value));
        return this;
    }

    public LinkUpdateMutation stringListProperty(String name, List<String> values) {
        properties.put(name, new StringListProperty(name, values));
        return this;
    }

    public LinkUpdateMutation intProperty(String name, Integer value) {
        properties.put(name, new IntProperty(name, value));
        return this;
    }

    public LinkUpdateMutation intListProperty(String name, List<Integer> values) {
        properties.put(name, new IntListProperty(name, values));
        return this;
    }

    public LinkUpdateMutation doubleProperty(String name, double value) {
        properties.put(name, new DoubleProperty(name, value));
        return this;
    }

    public LinkUpdateMutation doubleListProperty(String name, List<Double> values) {
        properties.put(name, new DoubleListProperty(name, values));
        return this;
    }

    public LinkUpdateMutation booleanProperty(String name, boolean value) {
        properties.put(name, new BooleanProperty(name, value));
        return this;
    }

    public LinkUpdateMutation booleanListProperty(String name, List<Boolean> values) {
        properties.put(name, new BooleanListProperty(name, values));
        return this;
    }

    public LinkUpdateMutation dateProperty(String name, Date value) {
        properties.put(name, new DateProperty(name, value));
        return this;
    }

    public LinkUpdateMutation dateListProperty(String name, List<Date> values) {
        properties.put(name, new DateListProperty(name, values));
        return this;
    }

    public LinkUpdateMutation binaryReferenceProperty(String name, Long nodeId) {
        properties.put(name, new BinaryReferenceProperty(name, nodeId));
        return this;
    }

    public Set<Property> getProperties() {
        return new HashSet<>(properties.values());
    }

}
