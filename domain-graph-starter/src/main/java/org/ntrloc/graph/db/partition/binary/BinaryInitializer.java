package org.ntrloc.graph.db.partition.binary;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class BinaryInitializer {

    private final JdbcClient jdbcClient;

    public BinaryInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS binary_content (
                    id         UUID PRIMARY KEY DEFAULT uuidv7(),
                    sha256     TEXT NOT NULL UNIQUE,
                    md5        TEXT NOT NULL,
                    mime_type  TEXT,
                    length     BIGINT NOT NULL,
                    metadata   JSONB,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """).update();

        jdbcClient.sql("CREATE INDEX IF NOT EXISTS binary_content_sha256_idx ON binary_content (sha256)").update();
    }
}
