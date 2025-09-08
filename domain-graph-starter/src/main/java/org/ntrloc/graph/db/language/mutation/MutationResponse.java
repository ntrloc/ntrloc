package org.ntrloc.graph.db.language.mutation;

import java.util.ArrayList;
import java.util.List;

public class MutationResponse {

    private List<ItemMutationResponse> itemMutationResponses = new ArrayList<>();
    private List<LinkMutationResponse> linkMutationResponses = new ArrayList<>();

    public void addItemMutationResponse(ItemMutationResponse itemMutationResponse) {
        itemMutationResponses.add(itemMutationResponse);
    }

    public void addLinkMutationResponse(LinkMutationResponse linkMutationResponse) {
        linkMutationResponses.add(linkMutationResponse);
    }

    public List<ItemMutationResponse> getItemMutationResponses() {
        return itemMutationResponses;
    }

    public List<LinkMutationResponse> getLinkMutationResponses() {
        return linkMutationResponses;
    }

}
