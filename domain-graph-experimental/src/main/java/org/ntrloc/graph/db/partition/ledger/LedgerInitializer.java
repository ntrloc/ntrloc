package org.ntrloc.graph.db.partition.ledger;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class LedgerInitializer {

    private final JdbcClient jdbcClient;

    public LedgerInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS ledger_entry (
                    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    sequence_number  BIGINT GENERATED ALWAYS AS IDENTITY,
                    target_type      TEXT NOT NULL,
                    target_id        UUID NOT NULL,
                    entry_type       TEXT NOT NULL,
                    payload          JSONB NOT NULL,
                    transaction_id   UUID NOT NULL,
                    state            TEXT NOT NULL,
                    commit_id        UUID,
                    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    actor_external_id TEXT
                )
                """).update();

        jdbcClient.sql("CREATE INDEX IF NOT EXISTS ledger_entry_target_idx ON ledger_entry (target_type, target_id, sequence_number)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS ledger_entry_transaction_idx ON ledger_entry (transaction_id)").update();
    }
}
