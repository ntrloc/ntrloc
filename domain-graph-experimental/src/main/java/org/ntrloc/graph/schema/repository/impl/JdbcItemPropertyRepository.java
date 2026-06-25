package org.ntrloc.graph.schema.repository.impl;

import org.ntrloc.graph.schema.definition.IdentifiedPropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyUsage;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.repository.ItemPropertyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JdbcItemPropertyRepository implements ItemPropertyRepository {

    private JdbcClient jdbcClient;

    public JdbcItemPropertyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void associate(UUID itemDefinitionId, UUID propertyDefinitionId) {
        jdbcClient
                .sql("INSERT INTO schema_item_property (item_definition_id, property_id) VALUES (:itemDefinitionId, :propertyDefinitionId)")
                .param("itemDefinitionId", itemDefinitionId)
                .param("propertyDefinitionId", propertyDefinitionId)
                .update();
    }

    @Override
    public void dissociate(UUID itemDefinitionId, UUID propertyDefinitionId) {
        jdbcClient
                .sql("DELETE FROM schema_item_property WHERE item_definition_id = :itemDefinitionId AND property_id = :propertyDefinitionId")
                .param("itemDefinitionId", itemDefinitionId)
                .param("propertyDefinitionId", propertyDefinitionId)
                .update();
    }

    @Override
    public List<IdentifiedPropertyDefinition> findByItemType(UUID itemDefinitionId) {
        return jdbcClient
                .sql("""
                        SELECT p.* FROM schema_property p
                        JOIN schema_item_property ip ON ip.property_id = p.id
                        WHERE ip.item_definition_id = :itemDefinitionId
                        """)
                .param("itemDefinitionId", itemDefinitionId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public Map<UUID, List<IdentifiedPropertyDefinition>> mapAllByItemType() {
        return jdbcClient
                .sql("""
                        SELECT ip.item_definition_id, p.*
                        FROM schema_property p
                        JOIN schema_item_property ip ON ip.property_id = p.id
                        """)
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
    public boolean exists(UUID itemDefinitionId, UUID propertyDefinitionId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM schema_item_property WHERE item_definition_id = :itemDefinitionId AND property_id = :propertyDefinitionId")
                .param("itemDefinitionId", itemDefinitionId)
                .param("propertyDefinitionId", propertyDefinitionId)
                .query(Integer.class)
                .single() > 0;
    }

    private IdentifiedPropertyDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IdentifiedPropertyDefinition(
                UUID.fromString(rs.getString("id")),
                new PropertyDefinition(
                    rs.getString("name"),
                    rs.getString("description"),
                    PropertyType.valueOf(rs.getString("type")),
                    PropertyCardinality.valueOf(rs.getString("cardinality")),
                    PropertyUsage.valueOf(rs.getString("usage"))
                )
        );
    }
}
