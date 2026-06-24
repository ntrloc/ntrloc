package org.ntrloc.graph.schema.repository.impl;

import org.ntrloc.graph.schema.definition.IdentifiedItemLinkPerspectiveDefinition;
import org.ntrloc.graph.schema.definition.ItemLinkPerspectiveDefinition;
import org.ntrloc.graph.schema.repository.ItemLinkPerspectiveDefinitionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JdbcItemLinkPerspectiveDefinitionRepository implements ItemLinkPerspectiveDefinitionRepository {

    private JdbcClient jdbcClient;

    public JdbcItemLinkPerspectiveDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public IdentifiedItemLinkPerspectiveDefinition create(ItemLinkPerspectiveDefinition definition) {
        return jdbcClient
                .sql("""
                        INSERT INTO schema_item_link_perspective (item_definition_id, link_definition_id, name, description, minimum_cardinality, maximum_cardinality)
                        VALUES (:itemDefinitionId, :linkId, :name, :description, :minCardinality, :maxCardinality)
                        RETURNING *
                        """)
                .param("itemDefinitionId", definition.itemDefinitionId())
                .param("linkId", definition.linkId())
                .param("name", definition.name())
                .param("description", definition.description())
                .param("minCardinality", definition.minCardinality())
                .param("maxCardinality", definition.maxCardinality())
                .query(this::mapRow)
                .single();
    }

    @Override
    public Optional<IdentifiedItemLinkPerspectiveDefinition> findById(UUID id) {
        return jdbcClient
                .sql("SELECT * FROM schema_item_link_perspective WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<IdentifiedItemLinkPerspectiveDefinition> findByLinkId(UUID linkId) {
        return jdbcClient
                .sql("SELECT * FROM schema_item_link_perspective WHERE link_definition_id = :linkId ORDER BY name")
                .param("linkId", linkId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public IdentifiedItemLinkPerspectiveDefinition findInversePerspective(UUID linkId, UUID itemLinkPerspectiveId) {
        return jdbcClient
                .sql("SELECT * FROM schema_item_link_perspective WHERE link_definition_id = :linkId AND id != :itemLinkPerspectiveId")
                .param("linkId", linkId)
                .param("itemLinkPerspectiveId", itemLinkPerspectiveId)
                .query(this::mapRow)
                .single();
    }

    @Override
    public Map<UUID, List<IdentifiedItemLinkPerspectiveDefinition>> mapAllByItemType() {
        return jdbcClient
                .sql("SELECT * FROM schema_item_link_perspective ORDER BY item_definition_id, name")
                .query((rs, rowNum) -> Map.entry(
                        UUID.fromString(rs.getString("item_definition_id")),
                        mapRow(rs, rowNum)
                ))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ));
    }

    @Override
    public IdentifiedItemLinkPerspectiveDefinition update(UUID id, ItemLinkPerspectiveDefinition definition) {
        return jdbcClient
                .sql("""
                        UPDATE schema_item_link_perspective
                        SET name = :name, minimum_cardinality = :minCardinality, maximum_cardinality = :maxCardinality
                        WHERE id = :id
                        RETURNING *
                        """)
                .param("id", id)
                .param("name", definition.name())
                .param("minCardinality", definition.minCardinality())
                .param("maxCardinality", definition.maxCardinality())
                .query(this::mapRow)
                .single();
    }

    @Override
    public void delete(UUID id) {
        jdbcClient
                .sql("DELETE FROM schema_item_link_perspective WHERE id = :id")
                .param("id", id)
                .update();
    }

    private IdentifiedItemLinkPerspectiveDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IdentifiedItemLinkPerspectiveDefinition(
                UUID.fromString(rs.getString("id")),
                new ItemLinkPerspectiveDefinition(
                        UUID.fromString(rs.getString("item_definition_id")),
                        UUID.fromString(rs.getString("link_definition_id")),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("minimum_cardinality"),
                        rs.getObject("maximum_cardinality", Integer.class)
                )
        );
    }
}
