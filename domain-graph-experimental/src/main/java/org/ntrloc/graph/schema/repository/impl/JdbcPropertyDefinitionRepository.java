package org.ntrloc.graph.schema.repository.impl;

import org.ntrloc.graph.schema.definition.IdentifiedPropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.repository.PropertyDefinitionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcPropertyDefinitionRepository implements PropertyDefinitionRepository {

    private JdbcClient jdbcClient;

    public JdbcPropertyDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public IdentifiedPropertyDefinition create(PropertyDefinition definition) {
        return jdbcClient
                .sql("""
                    INSERT INTO schema_property (name, type, cardinality)
                    VALUES (:name, :type, :cardinality)
                    RETURNING *
                 """)
                .param("name", definition.name())
                .param("type", definition.type().name())
                .param("cardinality", definition.cardinality().name())
                .query(this::mapRow)
                .single();
    }

    @Override
    public Optional<IdentifiedPropertyDefinition> findById(UUID id) {
        return jdbcClient
                .sql("SELECT * FROM schema_property WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public Optional<IdentifiedPropertyDefinition> findByName(String name) {
        return jdbcClient
                .sql("SELECT * FROM schema_property WHERE name = :name")
                .param("name", name)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<IdentifiedPropertyDefinition> findAll() {
        return jdbcClient
                .sql("SELECT * FROM schema_property ORDER BY name")
                .query(this::mapRow)
                .list();
    }

    @Override
    public IdentifiedPropertyDefinition updateName(UUID id, String name) {
        return jdbcClient
                .sql("UPDATE schema_property SET name = :name WHERE id = :id RETURNING *")
                .param("id", id)
                .param("name", name)
                .query(this::mapRow)
                .single();
    }

    @Override
    public void delete(UUID id) {
        jdbcClient
                .sql("DELETE FROM schema_property WHERE id = :id")
                .param("id", id)
                .update();
    }

    private IdentifiedPropertyDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IdentifiedPropertyDefinition(
                UUID.fromString(rs.getString("id")),
                new PropertyDefinition(
                    rs.getString("name"),
                    PropertyType.valueOf(rs.getString("type")),
                    PropertyCardinality.valueOf(rs.getString("cardinality"))
                )
        );
    }
}
