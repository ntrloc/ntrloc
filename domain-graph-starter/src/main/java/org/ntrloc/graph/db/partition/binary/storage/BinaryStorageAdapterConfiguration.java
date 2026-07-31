package org.ntrloc.graph.db.partition.binary.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "binary.storage.block")
public class BinaryStorageAdapterConfiguration {

    private String location;
    private boolean autocreate;

    public BinaryStorageAdapterConfiguration() {}

    public BinaryStorageAdapterConfiguration(String location, boolean autocreate) {
        this.location = location;
        this.autocreate = autocreate;
    }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public boolean isAutocreate() { return autocreate; }
    public void setAutocreate(boolean autocreate) { this.autocreate = autocreate; }
}
