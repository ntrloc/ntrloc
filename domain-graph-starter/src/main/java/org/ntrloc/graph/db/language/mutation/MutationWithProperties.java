package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;

import java.util.List;

public interface MutationWithProperties {

    List<Property> getProperties();
    void setProperties(List<Property> properties);

}
