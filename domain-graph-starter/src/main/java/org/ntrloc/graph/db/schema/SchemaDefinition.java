package org.ntrloc.graph.db.schema;

import java.util.Objects;

public abstract class SchemaDefinition {

    String uid;

    String name;

    String description;

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SchemaDefinition schema)) return false;
        return Objects.equals(name, schema.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
