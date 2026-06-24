package org.ntrloc.graph.schema.repository.impl;

import org.ntrloc.graph.schema.definition.IdentifiedItemDefinition;
import org.ntrloc.graph.schema.definition.ItemDefinition;
import org.ntrloc.graph.schema.repository.ItemDefinitionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class JdbcItemDefinitionRepository implements ItemDefinitionRepository {

    private JdbcClient jdbcClient;

    public JdbcItemDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Set<IdentifiedItemDefinition> getItemDefinitions() {
        return Set.copyOf(jdbcClient.sql("SELECT id, name FROM schema_item")
                .query((rs, rowNum) -> new IdentifiedItemDefinition(rs.getObject("id", UUID.class), new ItemDefinition(rs.getString("name"))))
                .list());
    }

    @Override
    public IdentifiedItemDefinition createItemDefinition(ItemDefinition itemDefinition) {
        boolean nameExists = jdbcClient.sql("SELECT COUNT(*) FROM schema_item WHERE name = :name")
                .param("name", itemDefinition.name())
                .query(Integer.class)
                .single() > 0;
        if (nameExists) {
            throw new IllegalArgumentException("Item with name '" + itemDefinition.name() + "' already exists");
        }
        UUID id = jdbcClient.sql("INSERT INTO schema_item (name) VALUES (:name) RETURNING id")
                .param("name", itemDefinition.name())
                .query(UUID.class)
                .single();
        return new IdentifiedItemDefinition(id, new ItemDefinition(itemDefinition.name()));
    }

    @Override
    public void deleteItemDefinition(UUID id) {
        jdbcClient.sql("DELETE FROM schema_item WHERE id = :id")
                .param("id", id)
                .update();
    }

}
