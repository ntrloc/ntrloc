package org.ntrloc.graph.db.traversal.mutator;

import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.List;

public interface Mutator {

    String createNode(String label, List<? extends Property> properties);

    /** Updates a node and returns the type and unique id of the updated node.*/
    Tuple<String, String> updateNode(Selector selector, List<? extends Property> properties);

    /** Deletes a node and returns the type and unique id of the updated node.*/
    Tuple<String, String> deleteNode(Selector selector);

    /** Creates a link between the given items and return the item type of the source item and the unique id of the link.*/
    Tuple<String, String> createLink(String fromItemId, String toItemId, String relationshipName, List<? extends Property> properties);

    void updateLink(Selector selector, List<Property> properties);

    void deleteLink(Selector selector);

    /* Transaction methods */
    String getTransactionId();
    void begin();
    void checkpoint();
    void prepare();
    List<MutationResult> commit();
    void abort();

}
