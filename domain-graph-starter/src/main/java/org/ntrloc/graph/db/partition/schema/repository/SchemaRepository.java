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

    public record ItemRow(UUID id, String name, String description, UUID supertypeId, boolean abstractType, String displayLabelPattern) {}

    public record TraitRow(UUID id, String name, String description) {}

    public record PerspectiveRow(UUID id, UUID entityId, UUID linkId, String name, String description, Integer minCardinality, Integer maxCardinality) {}

    // A property's or a group's single parent: an item type, a trait, a link type, or another group.
    public record PropertyOwnerRef(PropertyContainerKind kind, UUID ownerId) {}

    public record GroupRow(UUID id, String name, String description, PropertyOwnerRef parent) {}

    public record StateMachineRow(UUID id, UUID itemDefinitionId, String name, String description) {}

    public record StateRow(UUID id, UUID stateMachineId, String name, String description, String kind,
                           String entryProcessId, String exitProcessId, String entryMarkerDecisionKey) {}

    public record TransitionRow(UUID id, UUID fromStateId, UUID toStateId, String name, String description, String processId, String guardCondition) {}

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String PARAM_DESCRIPTION = "description";
    private static final String PARAM_TRAIT_ID = "traitId";
    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PARAM_USAGE = "usage";
    private static final String PARAM_CARDINALITY = "cardinality";
    private static final String COL_ITEM_DEFINITION_ID = "item_definition_id";
    private static final String COL_FACETABLE = "facetable";
    private static final String PARAM_LINK_ID = "linkId";
    private static final String PARAM_PARENT_ITEM_ID = "parentItemId";
    private static final String PARAM_PARENT_TRAIT_ID = "parentTraitId";
    private static final String PARAM_PARENT_LINK_ID = "parentLinkId";
    private static final String PARAM_PARENT_GROUP_ID = "parentGroupId";
    private static final String COL_PARENT_ITEM_ID = "parent_item_id";
    private static final String COL_PARENT_TRAIT_ID = "parent_trait_id";
    private static final String COL_PARENT_LINK_ID = "parent_link_id";
    private static final String COL_PARENT_GROUP_ID = "parent_group_id";

    private final JdbcClient jdbcClient;

    public SchemaRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Items ---

    public Set<ItemRow> getAllItems() {
        return Set.copyOf(jdbcClient.sql("SELECT id, name, description, supertype_id, abstract, display_label_pattern FROM schema_item")
                .query((rs, n) -> new ItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString(PARAM_DESCRIPTION),
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
        return new ItemRow(itemId, name, description, supertypeId, abstractType, displayLabelPattern);
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

    // --- Properties and property groups ---
    //
    // Every property and every group has exactly one parent, held in parent_* columns with a CHECK
    // that exactly one is non-null -- so a row is always created *with* its parent, and a move is a
    // single UPDATE that swaps which column is set. There is no separate associate/dissociate step.

    // Binds all four parent columns, exactly one of them non-null.
    private JdbcClient.StatementSpec bindParent(JdbcClient.StatementSpec spec, PropertyOwnerRef parent) {
        return spec
                .param(PARAM_PARENT_ITEM_ID, parent.kind() == PropertyContainerKind.ITEM ? parent.ownerId() : null)
                .param(PARAM_PARENT_TRAIT_ID, parent.kind() == PropertyContainerKind.TRAIT ? parent.ownerId() : null)
                .param(PARAM_PARENT_LINK_ID, parent.kind() == PropertyContainerKind.LINK ? parent.ownerId() : null)
                .param(PARAM_PARENT_GROUP_ID, parent.kind() == PropertyContainerKind.GROUP ? parent.ownerId() : null);
    }

    private static String parentColumn(PropertyContainerKind kind) {
        return switch (kind) {
            case ITEM -> COL_PARENT_ITEM_ID;
            case TRAIT -> COL_PARENT_TRAIT_ID;
            case LINK -> COL_PARENT_LINK_ID;
            case GROUP -> COL_PARENT_GROUP_ID;
        };
    }

    private PropertyOwnerRef parentOf(ResultSet rs) throws SQLException {
        for (PropertyContainerKind kind : PropertyContainerKind.values()) {
            UUID id = rs.getObject(parentColumn(kind), UUID.class);
            if (id != null) return new PropertyOwnerRef(kind, id);
        }
        throw new IllegalStateException("Row has no parent -- violates the exactly-one-parent CHECK");
    }

    public AdminPropertyDefinitionView createProperty(PropertyOwnerRef parent, String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable) {
        return bindParent(jdbcClient.sql("""
                INSERT INTO schema_property (name, description, type, cardinality, usage, facetable,
                    parent_item_id, parent_trait_id, parent_link_id, parent_group_id)
                VALUES (:name, :description, :type, :cardinality, :usage, :facetable,
                    :parentItemId, :parentTraitId, :parentLinkId, :parentGroupId) RETURNING *
                """), parent)
                .param("name", name).param(PARAM_DESCRIPTION, description)
                .param("type", type.name()).param(PARAM_CARDINALITY, cardinality.name()).param(PARAM_USAGE, usage.name())
                .param(COL_FACETABLE, facetable)
                .query(this::mapProperty)
                .single();
    }

    public AdminPropertyDefinitionView updateProperty(UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable) {
        return jdbcClient.sql("UPDATE schema_property SET name = :name, description = :description, type = :type, cardinality = :cardinality, usage = :usage, facetable = :facetable WHERE id = :id RETURNING *")
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("type", type.name()).param(PARAM_CARDINALITY, cardinality.name()).param(PARAM_USAGE, usage.name())
                .param(COL_FACETABLE, facetable)
                .query(this::mapProperty)
                .single();
    }

    // register_binary_property and every marker_grant_* row cascade on schema_property.id, so
    // deleting the row here is all this needs.
    public void deleteProperty(UUID id) {
        jdbcClient.sql("DELETE FROM schema_property WHERE id = :id").param("id", id).update();
    }

    public Optional<AdminPropertyDefinitionView> findProperty(UUID id) {
        return jdbcClient.sql("SELECT * FROM schema_property WHERE id = :id")
                .param("id", id)
                .query(this::mapProperty)
                .optional();
    }

    public Optional<PropertyOwnerRef> findPropertyParent(UUID propertyId) {
        return jdbcClient.sql("SELECT * FROM schema_property WHERE id = :id")
                .param("id", propertyId)
                .query((rs, n) -> parentOf(rs))
                .optional();
    }

    public void moveProperty(UUID propertyId, PropertyOwnerRef newParent) {
        bindParent(jdbcClient.sql("""
                UPDATE schema_property SET parent_item_id = :parentItemId, parent_trait_id = :parentTraitId,
                    parent_link_id = :parentLinkId, parent_group_id = :parentGroupId WHERE id = :id
                """), newParent)
                .param("id", propertyId)
                .update();
    }

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByItem() {
        return propertiesGroupedBy(COL_PARENT_ITEM_ID);
    }

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByTrait() {
        return propertiesGroupedBy(COL_PARENT_TRAIT_ID);
    }

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByLink() {
        return propertiesGroupedBy(COL_PARENT_LINK_ID);
    }

    public Map<UUID, List<AdminPropertyDefinitionView>> getPropertiesByGroup() {
        return propertiesGroupedBy(COL_PARENT_GROUP_ID);
    }

    // column is one of this class's own COL_PARENT_* constants, never caller-supplied.
    private Map<UUID, List<AdminPropertyDefinitionView>> propertiesGroupedBy(String column) {
        return jdbcClient.sql("SELECT * FROM schema_property WHERE " + column + " IS NOT NULL")
                .query((rs, n) -> Map.entry(rs.getObject(column, UUID.class), mapProperty(rs, n)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    public GroupRow createGroup(PropertyOwnerRef parent, String name, String description) {
        return bindParent(jdbcClient.sql("""
                INSERT INTO schema_property_group (name, description, parent_item_id, parent_trait_id, parent_link_id, parent_group_id)
                VALUES (:name, :description, :parentItemId, :parentTraitId, :parentLinkId, :parentGroupId) RETURNING *
                """), parent)
                .param("name", name).param(PARAM_DESCRIPTION, description)
                .query((rs, n) -> mapGroup(rs))
                .single();
    }

    public GroupRow updateGroup(UUID id, String name, String description) {
        return jdbcClient.sql("UPDATE schema_property_group SET name = :name, description = :description WHERE id = :id RETURNING *")
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
                .query((rs, n) -> mapGroup(rs))
                .single();
    }

    public void deleteGroup(UUID id) {
        jdbcClient.sql("DELETE FROM schema_property_group WHERE id = :id").param("id", id).update();
    }

    public Optional<GroupRow> findGroup(UUID id) {
        return jdbcClient.sql("SELECT * FROM schema_property_group WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> mapGroup(rs))
                .optional();
    }

    public void moveGroup(UUID groupId, PropertyOwnerRef newParent) {
        bindParent(jdbcClient.sql("""
                UPDATE schema_property_group SET parent_item_id = :parentItemId, parent_trait_id = :parentTraitId,
                    parent_link_id = :parentLinkId, parent_group_id = :parentGroupId WHERE id = :id
                """), newParent)
                .param("id", groupId)
                .update();
    }

    public Map<UUID, List<GroupRow>> getGroupsByItem() {
        return groupsGroupedBy(COL_PARENT_ITEM_ID);
    }

    public Map<UUID, List<GroupRow>> getGroupsByTrait() {
        return groupsGroupedBy(COL_PARENT_TRAIT_ID);
    }

    public Map<UUID, List<GroupRow>> getGroupsByLink() {
        return groupsGroupedBy(COL_PARENT_LINK_ID);
    }

    public Map<UUID, List<GroupRow>> getGroupsByGroup() {
        return groupsGroupedBy(COL_PARENT_GROUP_ID);
    }

    // column is one of this class's own COL_PARENT_* constants, never caller-supplied.
    private Map<UUID, List<GroupRow>> groupsGroupedBy(String column) {
        return jdbcClient.sql("SELECT * FROM schema_property_group WHERE " + column + " IS NOT NULL")
                .query((rs, n) -> Map.entry(rs.getObject(column, UUID.class), mapGroup(rs)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // child group -> parent group, for the containment-cycle guard (only group->group edges can form
    // a cycle -- a property is always a leaf).
    public Map<UUID, UUID> getParentGroupIdByGroup() {
        return jdbcClient.sql("SELECT id, parent_group_id FROM schema_property_group WHERE parent_group_id IS NOT NULL")
                .query((rs, n) -> Map.entry(
                        rs.getObject("id", UUID.class),
                        rs.getObject(COL_PARENT_GROUP_ID, UUID.class)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public boolean isGroupEmpty(UUID groupId) {
        return !Boolean.TRUE.equals(jdbcClient.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM schema_property WHERE parent_group_id = :groupId
                    UNION ALL
                    SELECT 1 FROM schema_property_group WHERE parent_group_id = :groupId
                )
                """)
                .param("groupId", groupId)
                .query(Boolean.class).single());
    }

    // Names of every property and group directly under one parent -- the sibling set that must stay
    // unique. Properties and groups live in separate tables, so this cross-table check can only be
    // enforced by the application (see SchemaMutationValidation), not by a DB constraint.
    public Set<String> findChildNames(PropertyOwnerRef parent) {
        String column = parentColumn(parent.kind());
        return Set.copyOf(jdbcClient.sql(
                        "SELECT name FROM schema_property WHERE " + column + " = :parentId "
                                + "UNION ALL SELECT name FROM schema_property_group WHERE " + column + " = :parentId")
                .param("parentId", parent.ownerId())
                .query(String.class)
                .list());
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

    public static final String STATE_KIND_NORMAL = "NORMAL";
    public static final String STATE_KIND_START = "START";
    public static final String STATE_KIND_END = "END";
    public static final String START_STATE_NAME = "__start__";
    public static final String END_STATE_NAME = "__end__";

    public StateRow createState(UUID stateMachineId, String name, String description, String kind,
                                String entryProcessId, String exitProcessId, String entryMarkerDecisionKey) {
        UUID id = jdbcClient.sql("""
                INSERT INTO schema_state (state_machine_id, name, description, kind, entry_process_id, exit_process_id, entry_marker_decision_key)
                VALUES (:stateMachineId, :name, :description, :kind, :entryProcessId, :exitProcessId, :entryMarkerDecisionKey) RETURNING id
                """)
                .param("stateMachineId", stateMachineId).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("kind", kind).param("entryProcessId", entryProcessId).param("exitProcessId", exitProcessId)
                .param("entryMarkerDecisionKey", entryMarkerDecisionKey)
                .query(UUID.class).single();
        return new StateRow(id, stateMachineId, name, description, kind, entryProcessId, exitProcessId, entryMarkerDecisionKey);
    }

    // The START/END pseudostates a machine is born with -- sentinel name, no processes/decisions, undeletable.
    public StateRow createPseudoState(UUID stateMachineId, String kind) {
        String name = STATE_KIND_START.equals(kind) ? START_STATE_NAME : END_STATE_NAME;
        return createState(stateMachineId, name, null, kind, null, null, null);
    }

    // Only NORMAL states are updatable through this -- name/description/entry/exit/marker-decision, never kind.
    public void updateState(UUID id, String name, String description, String entryProcessId, String exitProcessId,
                            String entryMarkerDecisionKey) {
        jdbcClient.sql("""
                UPDATE schema_state SET name = :name, description = :description,
                    entry_process_id = :entryProcessId, exit_process_id = :exitProcessId,
                    entry_marker_decision_key = :entryMarkerDecisionKey WHERE id = :id
                """)
                .param("id", id).param("name", name).param(PARAM_DESCRIPTION, description)
                .param("entryProcessId", entryProcessId).param("exitProcessId", exitProcessId)
                .param("entryMarkerDecisionKey", entryMarkerDecisionKey)
                .update();
    }

    public void deleteState(UUID id) {
        jdbcClient.sql("DELETE FROM schema_state WHERE id = :id").param("id", id).update();
    }

    public Optional<StateRow> findState(UUID id) {
        return jdbcClient.sql("SELECT * FROM schema_state WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> mapState(rs)).optional();
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

    public Optional<TransitionRow> findTransition(UUID id) {
        return jdbcClient.sql("SELECT * FROM schema_state_transition WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> mapTransition(rs)).optional();
    }

    public Map<UUID, List<TransitionRow>> getTransitionsByFromState() {
        return jdbcClient.sql("SELECT * FROM schema_state_transition ORDER BY from_state_id, name")
                .query((rs, n) -> Map.entry(rs.getObject("from_state_id", UUID.class), mapTransition(rs)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // --- Row mappers ---

    private AdminPropertyDefinitionView mapProperty(ResultSet rs, int n) throws SQLException {
        return new AdminPropertyDefinitionView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString(PARAM_DESCRIPTION),
                PropertyType.valueOf(rs.getString("type")),
                PropertyCardinality.valueOf(rs.getString(PARAM_CARDINALITY)),
                PropertyUsage.valueOf(rs.getString(PARAM_USAGE)),
                null,
                rs.getObject("controlled_list_id", UUID.class),
                rs.getBoolean(COL_FACETABLE)
        );
    }

    private GroupRow mapGroup(ResultSet rs) throws SQLException {
        return new GroupRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString(PARAM_DESCRIPTION),
                parentOf(rs)
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
                rs.getString("kind"),
                rs.getString("entry_process_id"),
                rs.getString("exit_process_id"),
                rs.getString("entry_marker_decision_key")
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
        // A JSON null (NullNode, e.g. from a REST payload's "guardCondition": null) is "no guard",
        // same as a Java null -- store real SQL NULL, not the string "null".
        if (node == null || node.isNull()) return null;
        return node.toString();
    }
}
