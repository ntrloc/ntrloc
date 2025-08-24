package org.ntrloc.graph.db.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "graph.storage")
public class StorageConfiguration {

    private BerkeleyStorageConfiguration berkeley;
    private CassandraStorageBackend cassandra;

    public BerkeleyStorageConfiguration getBerkeley() {
        return berkeley;
    }

    public void setBerkeley(BerkeleyStorageConfiguration berkeleyStorageConfiguration) {
        this.berkeley = berkeleyStorageConfiguration;
    }

    public CassandraStorageBackend getCassandra() {
        return cassandra;
    }

    public void setCassandraS(CassandraStorageBackend cassandraStorageBackend) {
        this.cassandra = cassandraStorageBackend;
    }

}
