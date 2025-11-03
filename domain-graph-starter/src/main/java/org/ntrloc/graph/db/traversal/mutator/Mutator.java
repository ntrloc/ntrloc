package org.ntrloc.graph.db.traversal.mutator;

import org.ntrloc.graph.db.language.Property;

import java.util.List;

public interface Mutator {

    String createNode(String label, List<? extends Property> properties);
    void updateNode(String uniqueId, List<? extends Property> properties);
    String deleteNode(String uniqueId);

    String createLink(String fromItemId, String toItemId, String relationshipName, List<? extends Property> properties);

    /* Transaction methods */
    String getTransactionId();
    void begin();
    void checkpoint();
    void prepare();
    List<MutationResult> commit();
    void abort();

}
