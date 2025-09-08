package org.ntrloc.graph.db.language.mutation;

import java.util.List;

public class MutationRequest {

    private List<ItemMutation> itemMutations;

    public MutationRequest(List<ItemMutation> itemMutations) {
        this.itemMutations = itemMutations;
    }

    public List<ItemMutation> getItemMutations() {
        return itemMutations == null ? List.of() : itemMutations;
    }

    public void setItemMutations(List<ItemMutation> itemMutations) {
        this.itemMutations = itemMutations;
    }

}
