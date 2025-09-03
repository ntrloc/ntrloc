package org.ntrloc.graph.db.language.mutation;

import java.util.ArrayList;
import java.util.List;

public class MutationResponse {

    private List<MutationResponseItem> itemList = new ArrayList<>();

    public void addItem(MutationResponseItem item) {
        itemList.add(item);
    }

    public List<MutationResponseItem> getItems() {
        return itemList;
    }

}
