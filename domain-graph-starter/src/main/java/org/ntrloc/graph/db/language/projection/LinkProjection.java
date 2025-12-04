package org.ntrloc.graph.db.language.projection;

import java.util.Map;

public abstract class LinkProjection {

    protected String id;

    protected String commitId;

    protected String linkType;

    protected Map<String, Object> properties;

    LinkProjection() {
        // no-op
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCommitId() {
        return commitId;
    }

    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

}
