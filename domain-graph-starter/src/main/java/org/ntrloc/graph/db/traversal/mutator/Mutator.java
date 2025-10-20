package org.ntrloc.graph.db.traversal.mutator;

import org.ntrloc.graph.db.language.Property;

import java.util.List;
import java.util.Set;

public interface Mutator {

    String createNode(String label, Set<? extends Property> properties);
    void updateNode(String uniqueId, Set<? extends Property> properties);
    String deleteNode(String uniqueId);

    String createLink(String fromItemId, String toItemId, String relationshipName, Set<? extends Property> properties);

    /* Transaction methods */
    String getTransactionId();
    void begin();
    void checkpoint();
    void prepare();
    List<MutationResult> commit();
    void abort();

}
