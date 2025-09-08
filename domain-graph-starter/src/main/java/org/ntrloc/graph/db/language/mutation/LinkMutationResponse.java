package org.ntrloc.graph.db.language.mutation;

public class LinkMutationResponse {

    MutationType type;
    String linkId;
    String fromId;
    String toId;
    String linkType;

    public LinkMutationResponse() {
        // no-op
    }

    public LinkMutationResponse(MutationType type, String linkId, String fromId, String toId, String linkType) {
        this.type = type;
        this.linkId = linkId;
        this.fromId = fromId;
        this.toId = toId;
        this.linkType = linkType;
    }

    public MutationType getType() {
        return type;
    }

    public void setType(MutationType type) {
        this.type = type;
    }

    public String getLinkId() {
        return linkId;
    }

    public void setLinkId(String linkId) {
        this.linkId = linkId;
    }

    public String getFromId() {
        return fromId;
    }

    public void setFromId(String fromId) {
        this.fromId = fromId;
    }

    public String getToId() {
        return toId;
    }

    public void setToId(String toId) {
        this.toId = toId;
    }

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }
}
