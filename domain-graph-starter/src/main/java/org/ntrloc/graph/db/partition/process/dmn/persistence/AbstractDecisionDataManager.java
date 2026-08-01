package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.persistence.entity.Entity;
import org.ntrloc.graph.db.partition.process.persistence.ProcessSession;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

// DMN-side counterpart of AbstractProcessDataManager -- deliberately duplicated (not imported)
// since the original is package-private in a different package. Reuses ProcessSession/
// ProcessSessionFactory as-is: Session/SessionFactory are shared Flowable base types with no
// process-engine-specific state, and the same ProcessSessionFactory instance is registered on
// both the process engine and the DMN engine (see DmnEngineConfig) -- Context.getCommandContext()
// resolves to whichever engine's command is currently running, and that command's own
// ProcessSession is what's returned here regardless of which engine started it.
abstract class AbstractDecisionDataManager {

    protected ProcessSession session() {
        return Context.getCommandContext().getSession(ProcessSession.class);
    }

    protected JdbcClient jdbcClient() {
        return session().jdbcClient();
    }

    protected void assignIdIfMissing(Entity entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
        }
    }
}
