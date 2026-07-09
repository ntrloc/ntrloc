package org.ntrloc.graph.db.partition.schema.repository;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class SchemaRepository {

    public record ItemRow(UUID id, UUID entityId, String name, String description) {}

    public record TraitRow(UUID id, UUID entityId, String name, String description) {}

    public record PerspectiveRow(UUID id, UUID entityId, UUID linkId, String name, String description, Integer minCardinality, Integer maxCardinality) {}

    private final JdbcClient jdbcClient;

    public SchemaRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Items ---

    public Set<ItemRow> getAllItems() {
        return Set.copyOf(jdbcClient.sql("SELECT id, entity_id, name, description FROM schema_item")
                .query((rs, n) -> new ItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("description")))
                .list());
    }

    public ItemRow createItem(String name, String description) {
        UUID entityId = jdbcClient.sql("INSERT INTO schema_entity DEFAULT VALUES RETURNING id")
                .query(UUID.class).single();
        UUID itemId = jdbcClient.sql("INSERT INTO schema_item (entity_id, name, description) VALUES (:entityId, :name, :description) RETURNING id")
                .param("entityId", entityId).param("name", name).param("description", description)
                .query(UUID.class).single();
        return new ItemRow(itemId, entityId, name, description);
    }

    public void updateItem(UUID id, String name, String description) {
        jdbcClient.sql("UPDATE schema_item SET name = :name, description = :description WHERE id = :id")
                .param("id", id).param("name", name).param("description", description)
                .update();
    }

    public void deleteItem(UUID id) {
        jdbcClient.sql("DELETE FROM schema_item WHERE id = :id").param("id", id).update();
    }

    // --- Traits ---

    public Set<TraitRow> getAllTraits() {
        return Set.copyOf(jdbcClient.sql("SELECT id, entity_id, name, description FROM schema_trait")
                .query((rs, n) -> new TraitRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("description")))
                .list());
    }

    public TraitRow createTrait(String name, String description) {
        UUID entityId = jdbcClient.sql("INSERT INTO schema_entity DEFAULT VALUES RETURNING id")
                .query(UUID.class).single();
        UUID traitId = jdbcClient.sql("INSERT INTO schema_trait (entity_id, name, description) VALUES (:entityId, :name, :description) RETURNING id")
                .param("entityId", entityId).param("name", name).param("description", description)
                .query(UUID.class).single();
        return new TraitRow(traitId, entityId, name, description);
    }

    public void implementTrait(UUID itemId, UUID traitId) {
        jdbcClient.sql("INSERT INTO schema_item_trait (item_id, trait_id) VALUES (:itemId, :traitId)")
                .param("itemId", itemId).param("traitId", traitId).update();
    }

    public void removeTrait(UUID itemId, UUID traitId) {
        jdbcClient.sql("DELETE FROM schema_item_trait WHERE item_id = :itemId AND trait_id = :traitId")
                .param("itemId", itemId).param("traitId", traitId).update();
    }

    public Map<UUID, List<UUID>> getTraitIdsByItem() {
        return jdbcClient.sql("SELECT item_id, trait_id FROM schema_item_trait")
                .query((rs, n) -> Map.entry(
                        rs.getObject("item_id", UUID.class),
                        rs.getObject("trait_id", UUID.class)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // --- Properties ---

    public AdminPropertyDefinitionView createProperty(String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage) {
        return jdbcClient.sql("INSERT INTO schema_property (name, description, type, cardinality, usage) VALUES (:name, :description, :type, :cardinality, :usage) RETURNING *")
                .param("name", name).param("description", description)
                .param("type", type.name()).param("cardinality", cardinality.name()).param("usage", usage.name())
                .query(this::mapProperty)
                .single();
    }

    public AdminPropertyDefinitionView updateProperty(UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage) {
        return jdbcClient.sql("UPDATE schema_property SET name = :name, description = :description, type = :type, cardinality = :cardinality, usage = :usage WHERE id = :id RETURNING *")
                .param("id", id).param("name", name).param("description", description)
                .param("type", type.name()).param("cardinality", cardinality.name()).param("usage", usage.name())
                .query(this::mapProperty)
                .single();
    }

    public void deleteProperty(UUID id) {
        jdbcClient.sql("DELETE FROM schema_property WHERE id = :id").param("id", id).update();
    }

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByItem() {
        return jdbcClient.sql("""
                SELECT ip.item_definition_id, p.*
                FROM schema_property p
                JOIN schema_item_property ip ON ip.property_id = p.id
                """)
                .query((rs, n) -> Map.entry(
                        rs.getObject("item_definition_id", UUID.class),
                        mapProperty(rs, n)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByTrait() {
        return jdbcClient.sql("""
                SELECT tp.trait_id, p.*
                FROM schema_property p
                JOIN schema_trait_property tp ON tp.property_id = p.id
                """)
                .query((rs, n) -> Map.entry(
                        rs.getObject("trait_id", UUID.class),
                        mapProperty(rs, n)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByLink() {
        return jdbcClient.sql("""
                SELECT lp.link_definition_id, p.*
                FROM schema_property p
                JOIN schema_link_property lp ON lp.property_id = p.id
                """)
                .query((rs, n) -> Map.entry(
                        rs.getObject("link_definition_id", UUID.class),
                        mapProperty(rs, n)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    public void associateItemProperty(UUID itemId, UUID propertyId) {
        jdbcClient.sql("INSERT INTO schema_item_property (item_definition_id, property_id) VALUES (:itemId, :propertyId)")
                .param("itemId", itemId).param("propertyId", propertyId).update();
    }

    public void associateTraitProperty(UUID traitId, UUID propertyId) {
        jdbcClient.sql("INSERT INTO schema_trait_property (trait_id, property_id) VALUES (:traitId, :propertyId)")
                .param("traitId", traitId).param("propertyId", propertyId).update();
    }

    public void dissociateItemProperty(UUID itemId, UUID propertyId) {
        jdbcClient.sql("DELETE FROM schema_item_property WHERE item_definition_id = :itemId AND property_id = :propertyId")
                .param("itemId", itemId).param("propertyId", propertyId).update();
    }

    // --- Links ---

    public Set<UUID> getAllLinkIds() {
        return Set.copyOf(jdbcClient.sql("SELECT id FROM schema_link")
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .list());
    }

    public UUID createLink() {
        return jdbcClient.sql("INSERT INTO schema_link DEFAULT VALUES RETURNING id").query(UUID.class).single();
    }

    public void deleteLink(UUID id) {
        jdbcClient.sql("DELETE FROM schema_link WHERE id = :id").param("id", id).update();
    }

    public void associateLinkProperty(UUID linkId, UUID propertyId) {
        jdbcClient.sql("INSERT INTO schema_link_property (link_definition_id, property_id) VALUES (:linkId, :propertyId)")
                .param("linkId", linkId).param("propertyId", propertyId).update();
    }

    public void dissociateLinkProperty(UUID linkId, UUID propertyId) {
        jdbcClient.sql("DELETE FROM schema_link_property WHERE link_definition_id = :linkId AND property_id = :propertyId")
                .param("linkId", linkId).param("propertyId", propertyId).update();
    }

    // --- Perspectives ---

    public PerspectiveRow createPerspective(UUID entityId, UUID linkId, String name, String description, Integer minCardinality, Integer maxCardinality) {
        return jdbcClient.sql("""
                INSERT INTO schema_entity_link_perspective (entity_id, link_definition_id, name, description, minimum_cardinality, maximum_cardinality)
                VALUES (:entityId, :linkId, :name, :description, :minCardinality, :maxCardinality) RETURNING *
                """)
                .param("entityId", entityId).param("linkId", linkId).param("name", name).param("description", description)
                .param("minCardinality", minCardinality).param("maxCardinality", maxCardinality)
                .query(this::mapPerspective)
                .single();
    }

    public PerspectiveRow updatePerspective(UUID id, String name, String description, Integer minCardinality, Integer maxCardinality) {
        return jdbcClient.sql("""
                UPDATE schema_entity_link_perspective SET name = :name, description = :description, minimum_cardinality = :minCardinality, maximum_cardinality = :maxCardinality
                WHERE id = :id RETURNING *
                """)
                .param("id", id).param("name", name).param("description", description)
                .param("minCardinality", minCardinality).param("maxCardinality", maxCardinality)
                .query(this::mapPerspective)
                .single();
    }

    public Map<UUID, List<PerspectiveRow>> getPerspectivesByEntity() {
        return jdbcClient.sql("SELECT * FROM schema_entity_link_perspective ORDER BY entity_id, name")
                .query((rs, n) -> Map.entry(rs.getObject("entity_id", UUID.class), mapPerspective(rs, n)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    public List<PerspectiveRow> findInversePerspectives(UUID linkId, UUID perspectiveId) {
        return jdbcClient.sql("SELECT * FROM schema_entity_link_perspective WHERE link_definition_id = :linkId AND id != :perspectiveId")
                .param("linkId", linkId).param("perspectiveId", perspectiveId)
                .query(this::mapPerspective)
                .list();
    }

    // --- Row mappers ---

    private AdminPropertyDefinitionView mapProperty(ResultSet rs, int n) throws SQLException {
        return new AdminPropertyDefinitionView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                PropertyType.valueOf(rs.getString("type")),
                PropertyCardinality.valueOf(rs.getString("cardinality")),
                PropertyUsage.valueOf(rs.getString("usage")),
                null,
                rs.getObject("controlled_list_id", UUID.class)
        );
    }

    private PerspectiveRow mapPerspective(ResultSet rs, int n) throws SQLException {
        return new PerspectiveRow(
                rs.getObject("id", UUID.class),
                rs.getObject("entity_id", UUID.class),
                rs.getObject("link_definition_id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("minimum_cardinality"),
                rs.getObject("maximum_cardinality", Integer.class)
        );
    }
}
