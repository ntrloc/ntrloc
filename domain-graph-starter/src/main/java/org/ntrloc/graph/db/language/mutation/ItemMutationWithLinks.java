package org.ntrloc.graph.db.language.mutation;

import java.util.List;

public interface ItemMutationWithLinks<T extends LinkMutation> {

    List<T> getLinks();
    void setLinks(List<T> links);

}
