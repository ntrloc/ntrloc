package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LinkUpdateMutation extends LinkMutation {

    private String id;
    private Map<String, Property> properties = new HashMap<>();

    public LinkUpdateMutation id(String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

    public Set<Property> getProperties() {
        return new HashSet<>(properties.values());
    }

}
