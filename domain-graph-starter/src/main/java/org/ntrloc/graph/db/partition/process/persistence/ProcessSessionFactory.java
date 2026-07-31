package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.common.engine.impl.interceptor.Session;
import org.flowable.common.engine.impl.interceptor.SessionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ProcessSessionFactory implements SessionFactory {

    private final JdbcClient jdbcClient;

    public ProcessSessionFactory(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Class<?> getSessionType() {
        return ProcessSession.class;
    }

    @Override
    public Session openSession(CommandContext commandContext) {
        return new ProcessSession(jdbcClient);
    }
}
