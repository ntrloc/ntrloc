package org.ntrloc.graph.db.traversal.mutator;

import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.List;

public interface Mutator {

    String createNode(String label, List<? extends Property> properties);

    /** Updates a node and returns the type and unique id of the updated node.*/
    Tuple<String, String> updateNode(Selector selector, List<? extends Property> properties);

    String deleteNode(String uniqueId);

    String createLink(String fromItemId, String toItemId, String relationshipName, List<? extends Property> properties);
    void updateLink(String linkId, List<Property> properties);

    /* Transaction methods */
    String getTransactionId();
    void begin();
    void checkpoint();
    void prepare();
    List<MutationResult> commit();
    void abort();

}
