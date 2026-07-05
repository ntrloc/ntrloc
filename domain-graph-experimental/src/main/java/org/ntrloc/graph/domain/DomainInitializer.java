package org.ntrloc.graph.domain;

import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.schema.ControlledListManager;
import org.ntrloc.graph.schema.repository.SchemaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

public interface DomainInitializer {

    /**
     * Called during SchemaManager startup, after schema DDL tables exist.
     * Implementations create item types, traits, links, properties, and controlled lists.
     */
    void initSchema(SchemaRepository repo, ControlledListManager controlledListManager);

    /**
     * Called during RegisterInitializer startup, after register DDL tables and binary tables exist.
     * Implementations seed domain data via jdbcClient and may store binary content.
     */
    void initData(JdbcClient jdbcClient, BinaryPartitionManager binaryPartitionManager);
}
