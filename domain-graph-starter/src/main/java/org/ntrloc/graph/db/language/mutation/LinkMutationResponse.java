package org.ntrloc.graph.db.language.mutation;

public class LinkMutationResponse {

    MutationType type;
    String linkType;
    String linkid;
    String fromId;
    String toId;


    public LinkMutationResponse() {
        // no-op
    }

    public LinkMutationResponse(MutationType type, String linkType, String linkId, String fromId, String toId) {
        this.type = type;
        this.linkType = linkType;
        this.linkid = linkId;
        this.fromId = fromId;
        this.toId = toId;
    }

    public MutationType getType() {
        return type;
    }

    public void setType(MutationType type) {
        this.type = type;
    }

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }

    public String getLinkid() {
        return linkid;
    }

    public void setLinkid(String linkid) {
        this.linkid = linkid;
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
}
