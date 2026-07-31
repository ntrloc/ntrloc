package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.persistence.entity.Entity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

// Shared by every custom DataManager in this package -- exposes the current command's
// ProcessSession (JdbcClient + entity cache) via Flowable's thread-bound CommandContext,
// the same way Flowable's own AbstractDataManager exposes a DbSqlSession.
abstract class AbstractProcessDataManager {

    protected ProcessSession session() {
        return Context.getCommandContext().getSession(ProcessSession.class);
    }

    protected JdbcClient jdbcClient() {
        return session().jdbcClient();
    }

    // Flowable's own DbSqlSession.insert(entity, idGenerator) is what normally assigns an
    // entity's id -- since that's bypassed entirely here, each insert() must do it instead.
    protected void assignIdIfMissing(Entity entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
        }
    }
}
