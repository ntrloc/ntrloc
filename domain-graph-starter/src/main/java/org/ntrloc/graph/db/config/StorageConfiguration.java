package org.ntrloc.graph.db.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "graph.storage")
public class StorageConfiguration {

    private BerkeleyStorageConfiguration berkeley;
    private CassandraStorageBackendConfiguration cassandra;

    public BerkeleyStorageConfiguration getBerkeley() {
        return berkeley;
    }

    public void setBerkeley(BerkeleyStorageConfiguration berkeleyStorageConfiguration) {
        this.berkeley = berkeleyStorageConfiguration;
    }

    public CassandraStorageBackendConfiguration getCassandra() {
        return cassandra;
    }

    public void setCassandraS(CassandraStorageBackendConfiguration cassandraStorageBackend) {
        this.cassandra = cassandraStorageBackend;
    }

}
