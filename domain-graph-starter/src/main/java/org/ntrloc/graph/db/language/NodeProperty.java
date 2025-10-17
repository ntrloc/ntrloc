package org.ntrloc.graph.db.language;

public interface NodeProperty extends Property<String> {

    String getName();
    String getNodeId();
    NodeProperty renamedTo(String name);

}
