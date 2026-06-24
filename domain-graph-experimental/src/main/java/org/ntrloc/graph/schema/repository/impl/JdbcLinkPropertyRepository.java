package org.ntrloc.graph.schema.repository.impl;

import org.ntrloc.graph.schema.definition.IdentifiedPropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyRequirement;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.repository.LinkPropertyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JdbcLinkPropertyRepository implements LinkPropertyRepository {

    private JdbcClient jdbcClient;

    public JdbcLinkPropertyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void associate(UUID linkDefinitionId, UUID propertyDefinitionId) {
        jdbcClient
                .sql("INSERT INTO schema_link_property (link_definition_id, property_id) VALUES (:linkDefinitionId, :propertyDefinitionId)")
                .param("linkDefinitionId", linkDefinitionId)
                .param("propertyDefinitionId", propertyDefinitionId)
                .update();
    }

    @Override
    public void dissociate(UUID linkDefinitionId, UUID propertyDefinitionId) {
        jdbcClient
                .sql("DELETE FROM schema_link_property WHERE link_definition_id = :linkDefinitionId AND property_id = :propertyDefinitionId")
                .param("linkDefinitionId", linkDefinitionId)
                .param("propertyDefinitionId", propertyDefinitionId)
                .update();
    }

    @Override
    public List<IdentifiedPropertyDefinition> findByLinkType(UUID linkDefinitionId) {
        return jdbcClient
                .sql("""
                        SELECT p.* FROM schema_property p
                        JOIN schema_link_property lp ON lp.property_id = p.id
                        WHERE lp.link_definition_id = :linkDefinitionId
                        """)
                .param("linkDefinitionId", linkDefinitionId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public Map<UUID, List<IdentifiedPropertyDefinition>> mapAllByLinkType() {
        return jdbcClient
                .sql("""
                        SELECT lp.link_definition_id, p.*
                        FROM schema_property p
                        JOIN schema_link_property lp ON lp.property_id = p.id
                        """)
                .query((rs, rowNum) -> Map.entry(
                        UUID.fromString(rs.getString("link_definition_id")),
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
    public boolean exists(UUID linkDefinitionId, UUID propertyDefinitionId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM schema_link_property WHERE link_definition_id = :linkDefinitionId AND property_id = :propertyDefinitionId")
                .param("linkDefinitionId", linkDefinitionId)
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
                    PropertyRequirement.valueOf(rs.getString("requirement"))
                )
        );
    }
}
