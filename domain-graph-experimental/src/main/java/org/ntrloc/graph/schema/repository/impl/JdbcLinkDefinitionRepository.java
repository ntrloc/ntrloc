package org.ntrloc.graph.schema.repository.impl;

import org.ntrloc.graph.schema.definition.LinkDefinition;
import org.ntrloc.graph.schema.repository.LinkDefinitionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class JdbcLinkDefinitionRepository implements LinkDefinitionRepository {

    private JdbcClient jdbcClient;

    public JdbcLinkDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Set<LinkDefinition> getLinkDefinitions() {
        return Set.copyOf(jdbcClient.sql("SELECT id FROM schema_link")
                .query((rs, rowNum) -> new LinkDefinition(rs.getObject("id", UUID.class)))
                .list());
    }

    @Override
    public LinkDefinition createLinkDefinition() {
        UUID id = jdbcClient.sql("INSERT INTO schema_link DEFAULT VALUES RETURNING id")
                .query(UUID.class)
                .single();
        return new LinkDefinition(id);
    }

    @Override
    public void deleteLinkDefinition(UUID id) {
        jdbcClient.sql("DELETE FROM schema_link WHERE id = :id")
                .param("id", id)
                .update();
    }

}
