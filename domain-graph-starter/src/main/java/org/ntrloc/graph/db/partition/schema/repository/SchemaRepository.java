package org.ntrloc.graph.db.partition.schema.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class SchemaRepository {

    public record ItemRow(UUID id, String name, String description, String initProcessId, UUID supertypeId, boolean abstractType, String displayLabelPattern) {}

    public record TraitRow(UUID id, String name, String description) {}

    public record PerspectiveRow(UUID id, UUID entityId, UUID linkId, String name, String description, Integer minCardinality, Integer maxCardinality) {}

    public record PropertyOwnerRef(PropertyContainerKind kind, UUID ownerId) {}

    public record StateMachineRow(UUID id, UUID itemDefinitionId, String name, String description) {}

    public record StateRow(UUID id, UUID stateMachineId, String name, String description, boolean isInitial, String entryProcessId, String exitProcessId) {}

    public record TransitionRow(UUID id, UUID fromStateId, UUID toStateId, String name, String description, String processId, String guardCondition) {}

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String PARAM_DESCRIPTION = "description";
    private static final String PARAM_TRAIT_ID = "traitId";
    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PARAM_USAGE = "usage";
    private static final String PARAM_CARDINALITY = "cardinality";
    private static final String COL_ITEM_DEFINITION_ID = "item_definition_id";
    private static final String PARAM_PROPERTY_ID = "propertyId";
    private static final String PARAM_LINK_ID = "linkId";

    private final JdbcClient jdbcClient;

    public SchemaRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Items ---

    public Set<ItemRow> getAllItems() {
        return Set.copyOf(jdbcClient.sql("SELECT id, name, description, init_process_id, supertype_id, abstract, display_label_pattern FROM schema_item")
                .query((rs, n) -> new ItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString(PARAM_DESCRIPTION),
                        rs.getString("init_process_id"),
                        rs.getObject("supertype_id", UUID.class),
                        rs.getBoolean("abstract"),
                        rs.getString("display_label_pattern")))
                .list());
    }

    // Convenience overload for the common case (no supertype, not abstract) -- keeps every
    // existing caller that predates inheritance compiling unchanged.
    public ItemRow createItem(String name, String description) {
        return createItem(name, description, null, false, null);
    }

    public ItemRow createItem(String name, String description, UUID supertypeId, boolean abstractType, String displayLabelPattern) {
        UUID itemId = jdbcClient.sql("""
                INSERT INTO schema_item (name, description, supertype_id, abstract, display_label_pattern)
                VALUES (:name, :description, :supertypeId, :abstractType, :displayLabelPattern) RETURNING id
                """)
                .param("name", name).param(PARAM_DESCRIPTION, description)
                .param("supertypeId", supertypeId).param("abstractType", abstractType)
                .param("displayLabelPattern", displayLabelPattern)
                .query(UUID.class).single();
        return new ItemRow(itemId, name, description, null, supertypeId, abstractType, displayLabelPattern);
    }

    public void setItemInitProcess(UUID itemId, String initProcessId) {
        jdbcClient.sql("UPDATE schema_item SET init_process_id = :initProcessId WHERE id = :id")
                .param("id", itemId).param("initProcessId", initProcessId).update();
    }

    // Convenience overload for the common case (no supertype, not abstract) -- keeps every
    // existing caller that predates inheritance compiling unchanged.
    public void updateItem(UUID id, String name, String description) {
        updateItem(id, name, description, null, false, null);
    }

    public void updateItem(UUID id, String name, String description, UUID supertypeId, boolean abstractType, String displayLabelPattern) {
        jdbcClient.sql("""
                UPDATE schema_item SET name = :name, description = :description,
                    supertype_id = :supertypeId, abstract = :abstractType, display_label_pattern = :displayLabelPattern WHERE id = :id
                """)
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("supertypeId", supertypeId).param("abstractType", abstractType)
                .param("displayLabelPattern", displayLabelPattern)
                .update();
    }

    public void deleteItem(UUID id) {
        jdbcClient.sql("DELETE FROM schema_item WHERE id = :id").param("id", id).update();
    }

    // register_item.item_type_id has no FK cascade (deliberately -- deleting an item type must
    // never touch already-persisted instance data), so this mirrors that same "in use" boundary
    // as an explicit, friendly check rather than relying on catching the FK violation it would
    // otherwise surface.
    public boolean isItemTypeInUse(UUID itemTypeId) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                SELECT EXISTS(SELECT 1 FROM register_item WHERE item_type_id = :itemTypeId AND state = 'COMMITTED')
                """)
                .param("itemTypeId", itemTypeId)
                .query(Boolean.class).single());
    }

    // --- Traits ---

    public Set<TraitRow> getAllTraits() {
        return Set.copyOf(jdbcClient.sql("SELECT id, name, description FROM schema_trait")
                .query((rs, n) -> new TraitRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString(PARAM_DESCRIPTION)))
                .list());
    }

    public TraitRow createTrait(String name, String description) {
        UUID traitId = jdbcClient.sql("INSERT INTO schema_trait (name, description) VALUES (:name, :description) RETURNING id")
                .param("name", name).param(PARAM_DESCRIPTION, description)
                .query(UUID.class).single();
        return new TraitRow(traitId, name, description);
    }

    public void deleteTrait(UUID id) {
        jdbcClient.sql("DELETE FROM schema_trait WHERE id = :id").param("id", id).update();
    }

    // Covers both ways a trait can be "in use": implemented by an item type (schema_item_trait),
    // or the target of a link perspective (schema_entity_link_perspective.entity_id -- deliberately
    // unconstrained by its own FK, since it's polymorphic across schema_item/schema_trait, so a
    // deleted trait referenced there would otherwise leave a silently orphaned perspective row
    // instead of a clean, caught error).
    public boolean isTraitInUse(UUID traitId) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM schema_item_trait WHERE trait_id = :traitId
                    UNION ALL
                    SELECT 1 FROM schema_entity_link_perspective WHERE entity_id = :traitId
                )
                """)
                .param(PARAM_TRAIT_ID, traitId)
                .query(Boolean.class).single());
    }

    public void implementTrait(UUID itemId, UUID traitId) {
        jdbcClient.sql("INSERT INTO schema_item_trait (item_id, trait_id) VALUES (:itemId, :traitId)")
                .param(PARAM_ITEM_ID, itemId).param(PARAM_TRAIT_ID, traitId).update();
    }

    public void removeTrait(UUID itemId, UUID traitId) {
        jdbcClient.sql("DELETE FROM schema_item_trait WHERE item_id = :itemId AND trait_id = :traitId")
                .param(PARAM_ITEM_ID, itemId).param(PARAM_TRAIT_ID, traitId).update();
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

    public AdminPropertyDefinitionView createProperty(String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable) {
        return jdbcClient.sql("INSERT INTO schema_property (name, description, type, cardinality, usage, facetable) VALUES (:name, :description, :type, :cardinality, :usage, :facetable) RETURNING *")
                .param("name", name).param(PARAM_DESCRIPTION, description)
                .param("type", type.name()).param(PARAM_CARDINALITY, cardinality.name()).param(PARAM_USAGE, usage.name())
                .param("facetable", facetable)
                .query(this::mapProperty)
                .single();
    }

    public AdminPropertyDefinitionView updateProperty(UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable) {
        return jdbcClient.sql("UPDATE schema_property SET name = :name, description = :description, type = :type, cardinality = :cardinality, usage = :usage, facetable = :facetable WHERE id = :id RETURNING *")
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("type", type.name()).param(PARAM_CARDINALITY, cardinality.name()).param(PARAM_USAGE, usage.name())
                .param("facetable", facetable)
                .query(this::mapProperty)
                .single();
    }

    public void deleteProperty(UUID id) {
        jdbcClient.sql("DELETE FROM schema_property WHERE id = :id").param("id", id).update();
    }

    public Optional<AdminPropertyDefinitionView> findProperty(UUID id) {
        return jdbcClient.sql("SELECT * FROM schema_property WHERE id = :id")
                .param("id", id)
                .query(this::mapProperty)
                .optional();
    }

    // A property's current container, regardless of kind -- whichever of the four association
    // tables actually references it. Assumes (as an application-maintained invariant, same as
    // every other association table here) that a property is associated with exactly one owner
    // at a time; move mutations preserve this by pairing a dissociate with an associate.
    public Optional<PropertyOwnerRef> findCurrentOwner(UUID propertyId) {
        return jdbcClient.sql("""
                SELECT 'ITEM' AS kind, item_definition_id AS owner_id FROM schema_item_property WHERE property_id = :propertyId
                UNION ALL
                SELECT 'TRAIT', trait_id FROM schema_trait_property WHERE property_id = :propertyId
                UNION ALL
                SELECT 'LINK', link_definition_id FROM schema_link_property WHERE property_id = :propertyId
                UNION ALL
                SELECT 'PROPERTY', parent_property_id FROM schema_property_property WHERE child_property_id = :propertyId
                """)
                .param("propertyId", propertyId)
                .query((rs, n) -> new PropertyOwnerRef(
                        PropertyContainerKind.valueOf(rs.getString("kind")),
                        rs.getObject("owner_id", UUID.class)))
                .optional();
    }

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByItem() {
        return jdbcClient.sql("""
                SELECT ip.item_definition_id, p.*
                FROM schema_property p
                JOIN schema_item_property ip ON ip.property_id = p.id
                """)
                .query((rs, n) -> Map.entry(
                        rs.getObject(COL_ITEM_DEFINITION_ID, UUID.class),
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
                .param(PARAM_ITEM_ID, itemId).param(PARAM_PROPERTY_ID, propertyId).update();
    }

    public void associateTraitProperty(UUID traitId, UUID propertyId) {
        jdbcClient.sql("INSERT INTO schema_trait_property (trait_id, property_id) VALUES (:traitId, :propertyId)")
                .param(PARAM_TRAIT_ID, traitId).param(PARAM_PROPERTY_ID, propertyId).update();
    }

    public void dissociateItemProperty(UUID itemId, UUID propertyId) {
        jdbcClient.sql("DELETE FROM schema_item_property WHERE item_definition_id = :itemId AND property_id = :propertyId")
                .param(PARAM_ITEM_ID, itemId).param(PARAM_PROPERTY_ID, propertyId).update();
    }

    public void dissociateTraitProperty(UUID traitId, UUID propertyId) {
        jdbcClient.sql("DELETE FROM schema_trait_property WHERE trait_id = :traitId AND property_id = :propertyId")
                .param(PARAM_TRAIT_ID, traitId).param(PARAM_PROPERTY_ID, propertyId).update();
    }

    // --- Object properties (property -> property containment) ---

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByProperty() {
        return jdbcClient.sql("""
                SELECT pp.parent_property_id, p.*
                FROM schema_property p
                JOIN schema_property_property pp ON pp.child_property_id = p.id
                """)
                .query((rs, n) -> Map.entry(
                        rs.getObject("parent_property_id", UUID.class),
                        mapProperty(rs, n)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    public void associatePropertyProperty(UUID parentPropertyId, UUID childPropertyId) {
        jdbcClient.sql("INSERT INTO schema_property_property (parent_property_id, child_property_id) VALUES (:parentPropertyId, :childPropertyId)")
                .param("parentPropertyId", parentPropertyId).param("childPropertyId", childPropertyId).update();
    }

    public void dissociatePropertyProperty(UUID parentPropertyId, UUID childPropertyId) {
        jdbcClient.sql("DELETE FROM schema_property_property WHERE parent_property_id = :parentPropertyId AND child_property_id = :childPropertyId")
                .param("parentPropertyId", parentPropertyId).param("childPropertyId", childPropertyId).update();
    }

    // Every property's current parent, regardless of container kind -- used by move validation and
    // by the cycle guard, which needs to walk "what owns this property" independent of whether that
    // owner is another property, an item, a trait, or a link (only the property->property edges are
    // relevant to a containment cycle, since only properties can themselves be containers).
    public Map<UUID, UUID> getParentPropertyIdByProperty() {
        return jdbcClient.sql("SELECT parent_property_id, child_property_id FROM schema_property_property")
                .query((rs, n) -> Map.entry(
                        rs.getObject("child_property_id", UUID.class),
                        rs.getObject("parent_property_id", UUID.class)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
                .param(PARAM_LINK_ID, linkId).param(PARAM_PROPERTY_ID, propertyId).update();
    }

    public void dissociateLinkProperty(UUID linkId, UUID propertyId) {
        jdbcClient.sql("DELETE FROM schema_link_property WHERE link_definition_id = :linkId AND property_id = :propertyId")
                .param(PARAM_LINK_ID, linkId).param(PARAM_PROPERTY_ID, propertyId).update();
    }

    // --- Perspectives ---

    public PerspectiveRow createPerspective(UUID entityId, UUID linkId, String name, String description, Integer minCardinality, Integer maxCardinality) {
        return jdbcClient.sql("""
                INSERT INTO schema_entity_link_perspective (entity_id, link_definition_id, name, description, minimum_cardinality, maximum_cardinality)
                VALUES (:entityId, :linkId, :name, :description, :minCardinality, :maxCardinality) RETURNING *
                """)
                .param("entityId", entityId).param(PARAM_LINK_ID, linkId).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("minCardinality", minCardinality).param("maxCardinality", maxCardinality)
                .query(this::mapPerspective)
                .single();
    }

    public PerspectiveRow findPerspectiveById(UUID id) {
        return jdbcClient.sql("SELECT * FROM schema_entity_link_perspective WHERE id = :id")
                .param("id", id)
                .query(this::mapPerspective)
                .single();
    }

    public PerspectiveRow updatePerspective(UUID id, String name, String description, Integer minCardinality, Integer maxCardinality) {
        return jdbcClient.sql("""
                UPDATE schema_entity_link_perspective SET name = :name, description = :description, minimum_cardinality = :minCardinality, maximum_cardinality = :maxCardinality
                WHERE id = :id RETURNING *
                """)
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
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
                .param(PARAM_LINK_ID, linkId).param("perspectiveId", perspectiveId)
                .query(this::mapPerspective)
                .list();
    }

    // Used by SchemaMutationValidation.requireConsistentPerspectiveTarget -- excludeLinkId lets
    // the caller ignore rows belonging to the link definition currently being created, whose own
    // perspectives are each other's target by construction and shouldn't be compared against
    // themselves via this lookup.
    public List<PerspectiveRow> findPerspectivesByEntityAndName(UUID entityId, String name, UUID excludeLinkId) {
        return jdbcClient.sql("""
                SELECT * FROM schema_entity_link_perspective
                WHERE entity_id = :entityId AND name = :name AND link_definition_id != :excludeLinkId
                """)
                .param("entityId", entityId).param("name", name).param("excludeLinkId", excludeLinkId)
                .query(this::mapPerspective)
                .list();
    }

    // --- State machines ---

    public StateMachineRow createStateMachine(UUID itemDefinitionId, String name, String description) {
        UUID id = jdbcClient.sql("""
                INSERT INTO schema_state_machine (item_definition_id, name, description)
                VALUES (:itemDefinitionId, :name, :description) RETURNING id
                """)
                .param("itemDefinitionId", itemDefinitionId).param("name", name).param(PARAM_DESCRIPTION, description)
                .query(UUID.class).single();
        return new StateMachineRow(id, itemDefinitionId, name, description);
    }

    public void updateStateMachine(UUID id, String name, String description) {
        jdbcClient.sql("UPDATE schema_state_machine SET name = :name, description = :description WHERE id = :id")
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
                .update();
    }

    public void deleteStateMachine(UUID id) {
        jdbcClient.sql("DELETE FROM schema_state_machine WHERE id = :id").param("id", id).update();
    }

    public Map<UUID, List<StateMachineRow>> getStateMachinesByItem() {
        return jdbcClient.sql("SELECT * FROM schema_state_machine ORDER BY item_definition_id, name")
                .query((rs, n) -> Map.entry(rs.getObject(COL_ITEM_DEFINITION_ID, UUID.class), mapStateMachine(rs)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // --- States ---

    public StateRow createState(UUID stateMachineId, String name, String description, boolean isInitial, String entryProcessId, String exitProcessId) {
        UUID id = jdbcClient.sql("""
                INSERT INTO schema_state (state_machine_id, name, description, is_initial, entry_process_id, exit_process_id)
                VALUES (:stateMachineId, :name, :description, :isInitial, :entryProcessId, :exitProcessId) RETURNING id
                """)
                .param("stateMachineId", stateMachineId).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("isInitial", isInitial).param("entryProcessId", entryProcessId).param("exitProcessId", exitProcessId)
                .query(UUID.class).single();
        return new StateRow(id, stateMachineId, name, description, isInitial, entryProcessId, exitProcessId);
    }

    public void updateState(UUID id, String name, String description, boolean isInitial, String entryProcessId, String exitProcessId) {
        jdbcClient.sql("""
                UPDATE schema_state SET name = :name, description = :description, is_initial = :isInitial,
                    entry_process_id = :entryProcessId, exit_process_id = :exitProcessId WHERE id = :id
                """)
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("isInitial", isInitial).param("entryProcessId", entryProcessId).param("exitProcessId", exitProcessId)
                .update();
    }

    public void deleteState(UUID id) {
        jdbcClient.sql("DELETE FROM schema_state WHERE id = :id").param("id", id).update();
    }

    public Map<UUID, List<StateRow>> getStatesByStateMachine() {
        return jdbcClient.sql("SELECT * FROM schema_state ORDER BY state_machine_id, name")
                .query((rs, n) -> Map.entry(rs.getObject("state_machine_id", UUID.class), mapState(rs)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // --- Transitions ---

    public TransitionRow createTransition(UUID fromStateId, UUID toStateId, String name, String description, String processId, String guardCondition) {
        UUID id = jdbcClient.sql("""
                INSERT INTO schema_state_transition (from_state_id, to_state_id, name, description, process_id, guard_condition)
                VALUES (:fromStateId, :toStateId, :name, :description, :processId, :guardCondition::jsonb) RETURNING id
                """)
                .param("fromStateId", fromStateId).param("toStateId", toStateId)
                .param("name", name).param(PARAM_DESCRIPTION, description)
                .param("processId", processId).param("guardCondition", guardCondition)
                .query(UUID.class).single();
        return new TransitionRow(id, fromStateId, toStateId, name, description, processId, guardCondition);
    }

    public void updateTransition(UUID id, String name, String description, String processId, String guardCondition) {
        jdbcClient.sql("""
                UPDATE schema_state_transition SET name = :name, description = :description,
                    process_id = :processId, guard_condition = :guardCondition::jsonb WHERE id = :id
                """)
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("processId", processId).param("guardCondition", guardCondition)
                .update();
    }

    public void deleteTransition(UUID id) {
        jdbcClient.sql("DELETE FROM schema_state_transition WHERE id = :id").param("id", id).update();
    }

    public Map<UUID, List<TransitionRow>> getTransitionsByFromState() {
        return jdbcClient.sql("SELECT * FROM schema_state_transition ORDER BY from_state_id, name")
                .query((rs, n) -> Map.entry(rs.getObject("from_state_id", UUID.class), mapTransition(rs)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // --- Row mappers ---

    // Children of an OBJECT-typed property aren't resolvable from this single-row query (they come
    // from a separate join, see getPropertiesByProperty) -- this always returns an empty list as a
    // placeholder, immediately replaced by SchemaViewBuilder's recursive child-resolution pass.
    private AdminPropertyDefinitionView mapProperty(ResultSet rs, int n) throws SQLException {
        return AdminPropertyDefinitionView.of(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString(PARAM_DESCRIPTION),
                PropertyType.valueOf(rs.getString("type")),
                PropertyCardinality.valueOf(rs.getString(PARAM_CARDINALITY)),
                PropertyUsage.valueOf(rs.getString(PARAM_USAGE)),
                null,
                rs.getObject("controlled_list_id", UUID.class),
                rs.getBoolean("facetable"),
                List.of()
        );
    }

    private PerspectiveRow mapPerspective(ResultSet rs, int n) throws SQLException {
        return new PerspectiveRow(
                rs.getObject("id", UUID.class),
                rs.getObject("entity_id", UUID.class),
                rs.getObject("link_definition_id", UUID.class),
                rs.getString("name"),
                rs.getString(PARAM_DESCRIPTION),
                rs.getInt("minimum_cardinality"),
                rs.getObject("maximum_cardinality", Integer.class)
        );
    }

    private StateMachineRow mapStateMachine(ResultSet rs) throws SQLException {
        return new StateMachineRow(
                rs.getObject("id", UUID.class),
                rs.getObject(COL_ITEM_DEFINITION_ID, UUID.class),
                rs.getString("name"),
                rs.getString(PARAM_DESCRIPTION)
        );
    }

    private StateRow mapState(ResultSet rs) throws SQLException {
        return new StateRow(
                rs.getObject("id", UUID.class),
                rs.getObject("state_machine_id", UUID.class),
                rs.getString("name"),
                rs.getString(PARAM_DESCRIPTION),
                rs.getBoolean("is_initial"),
                rs.getString("entry_process_id"),
                rs.getString("exit_process_id")
        );
    }

    private TransitionRow mapTransition(ResultSet rs) throws SQLException {
        return new TransitionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("from_state_id", UUID.class),
                rs.getObject("to_state_id", UUID.class),
                rs.getString("name"),
                rs.getString(PARAM_DESCRIPTION),
                rs.getString("process_id"),
                rs.getString("guard_condition")
        );
    }

    public JsonNode parseGuardCondition(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to parse guard condition JSON: " + json, e);
        }
    }

    public String serializeGuardCondition(JsonNode node) {
        if (node == null) return null;
        return node.toString();
    }
}
