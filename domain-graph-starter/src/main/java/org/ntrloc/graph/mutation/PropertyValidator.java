package org.ntrloc.graph.mutation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.authorization.RequestPermissionContext;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.springframework.stereotype.Component;

// Everything MutationRequestProcessor checks about a mutation's properties payload: resolving names to
// ids, each value's type/cardinality/controlled list/binary content, required and deprecated
// properties, and per-property write permission. Problems are added to the caller's error list, in
// the processor's accumulate-everything style.
@Component
class PropertyValidator {

    private static final String PROPERTY_QUOTE_PREFIX = "Property '";

    private final SchemaManager schemaManager;
    private final RegisterPartitionManager registerPartitionManager;
    private final PermissionService permissionService;
    private final BinaryPartitionManager binaryPartitionManager;
    private final ControlledListManager controlledListManager;

    PropertyValidator(SchemaManager schemaManager, RegisterPartitionManager registerPartitionManager, PermissionService permissionService,
                      BinaryPartitionManager binaryPartitionManager, ControlledListManager controlledListManager) {
        this.schemaManager = schemaManager;
        this.registerPartitionManager = registerPartitionManager;
        this.permissionService = permissionService;
        this.binaryPartitionManager = binaryPartitionManager;
        this.controlledListManager = controlledListManager;
    }

    private PropertyScope itemScope(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> PropertyScope.of(item.properties(), item.groups()))
                .orElse(PropertyScope.of(List.of(), List.of()));
    }

    Map<UUID, Object> resolveItemPropertyIds(UUID itemTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        return resolvePropertyIds(propertiesByName, itemScope(itemTypeId), path, errors);
    }

    private PropertyScope linkScope(UUID linkTypeId) {
        return schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> PropertyScope.of(link.properties(), link.groups()))
                .orElse(PropertyScope.of(List.of(), List.of()));
    }

    Map<UUID, Object> resolveLinkPropertyIds(UUID linkTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        return resolvePropertyIds(propertiesByName, linkScope(linkTypeId), path, errors);
    }

    // Link counterparts to checkRequiredItemProperties/checkRequiredItemPropertiesOnUpdate/
    // checkDeprecatedItemProperties -- same checks, scoped via linkScope instead of itemScope, and
    // checkRequiredLinkPropertiesOnUpdate merges onto the link's own current register row
    // (resolveMergedLinkPropertiesById) rather than an item's.
    void checkRequiredLinkProperties(UUID linkTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        checkRequiredProperties(linkScope(linkTypeId), propertiesByName, path, errors);
    }

    void checkRequiredLinkPropertiesOnUpdate(UUID linkTypeId, UUID linkId, Map<UUID, Object> propertiesDiff, String path, List<ValidationError> errors) {
        Map<UUID, Object> merged = registerPartitionManager.resolveMergedLinkPropertiesById(linkId, linkTypeId, propertiesDiff);
        checkRequiredPropertiesById(linkScope(linkTypeId), merged, path, errors);
    }

    void checkDeprecatedLinkProperties(UUID linkTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        checkDeprecatedProperties(linkScope(linkTypeId), propertiesByName, path, errors);
    }

    // Create only, never called for an update -- an update's own payload is a partial diff (a key
    // absent from it means "leave alone", not "clear"), so there is nothing to enforce there; an
    // explicit null on a REQUIRED property would leave the item invalid too, but that is a separate,
    // not-yet-built concern (PropertyValueValidator.resolvePropertyIds' null branch is the update-clear sentinel and
    // deliberately never type- or presence-checked). Walks the item type's *full* schema shape, not
    // just the payload's own keys the way PropertyValueValidator.resolvePropertyIds does -- that is what lets this notice a
    // REQUIRED property the payload never mentions at all, which resolvePropertyIds structurally
    // cannot (it only ever iterates keys actually present). Recurses into groups the same way
    // resolveGroupValue does, so a REQUIRED property nested inside a group is enforced identically to
    // a top-level one.
    void checkRequiredItemProperties(UUID itemTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        checkRequiredProperties(itemScope(itemTypeId), propertiesByName, path, errors);
    }

    // Absence only -- a key explicitly present with a null value is a different case (the
    // update-diff "clear this property" sentinel, rejected for a REQUIRED property by
    // PropertyValueValidator.resolvePropertyIds' null branch instead, which this deliberately leaves alone so the two
    // never both fire for the same property at once).
    private void checkRequiredProperties(PropertyScope scope, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        Map<String, Object> values = propertiesByName == null ? Map.of() : propertiesByName;
        for (AdminPropertyDefinitionView property : scope.properties().values()) {
            if (property.usage() != PropertyUsage.REQUIRED) continue;
            if (!values.containsKey(property.name())) {
                errors.add(new ValidationError(path + "." + property.name(), PROPERTY_QUOTE_PREFIX + property.name() + "' is required"));
            }
        }
        for (AdminPropertyGroupView group : scope.groups().values()) {
            Map<String, Object> nested = Map.of();
            if (values.get(group.name()) instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) rawMap;
                nested = cast;
            }
            checkRequiredProperties(PropertyScope.of(group.properties(), group.groups()), nested, path + "." + group.name(), errors);
        }
    }

    // The schema can change after an item already exists -- a property that was OPTIONAL (or didn't
    // exist at all) when an item was created can become REQUIRED later, without that item being
    // retroactively touched or invalidated (same philosophy as isAbstractItemType's own one-way
    // check: a schema change only ever blocks new writes, never rewrites or deletes existing data).
    // But the next write to *this* item has to leave it satisfying the schema as it stands today, so
    // this checks the item's own *resulting* state -- registerPartitionManager's own current
    // register row merged with this update's diff (resolveMergedPropertiesById, the same merge
    // PipelineMarkerDecisionService.enrichUpdate uses to evaluate pipeline predicates against an
    // about-to-be-committed item) -- not just the diff in isolation the way
    // checkRequiredItemProperties checks a create payload. A property this update's own diff never
    // mentions at all still has to end up with a real value, which is exactly what merging onto the
    // current row (rather than just checking the diff) catches.
    void checkRequiredItemPropertiesOnUpdate(UUID itemTypeId, UUID itemId, Map<UUID, Object> propertiesDiff, String path, List<ValidationError> errors) {
        Map<UUID, Object> merged = registerPartitionManager.resolveMergedPropertiesById(itemId, itemTypeId, propertiesDiff);
        checkRequiredPropertiesById(itemScope(itemTypeId), merged, path, errors);
    }

    // Property ids are globally unique regardless of which group (if any) they're nested under --
    // see resolveGroupValue's own "flat storage" comment -- so unlike checkRequiredProperties (which
    // has to narrow to each group's own name-scoped sub-map as it recurses), this only ever needs to
    // narrow the *schema* walk; valuesById itself stays the one flat map throughout.
    private void checkRequiredPropertiesById(PropertyScope scope, Map<UUID, Object> valuesById, String path, List<ValidationError> errors) {
        for (AdminPropertyDefinitionView property : scope.properties().values()) {
            if (property.usage() != PropertyUsage.REQUIRED) continue;
            if (valuesById.get(property.id()) == null) {
                errors.add(new ValidationError(path + "." + property.name(), PROPERTY_QUOTE_PREFIX + property.name() + "' must be set"));
            }
        }
        for (AdminPropertyGroupView group : scope.groups().values()) {
            checkRequiredPropertiesById(PropertyScope.of(group.properties(), group.groups()), valuesById, path + "." + group.name(), errors);
        }
    }

    // Presence, not absence -- the opposite of checkRequiredProperties, so this walks the payload's
    // own keys (the way PropertyValueValidator.resolvePropertyIds does) rather than the full schema shape: a deprecated
    // property the payload never mentions is fine. Applies to both create and update -- unlike
    // checkRequiredItemProperties, there's no partial-diff concern here, since this only ever reacts
    // to a key actually present in this specific payload. Recurses into groups the same way
    // resolveGroupValue does, so a DEPRECATED property nested inside a group is caught the same as a
    // top-level one.
    void checkDeprecatedItemProperties(UUID itemTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        checkDeprecatedProperties(itemScope(itemTypeId), propertiesByName, path, errors);
    }

    private void checkDeprecatedProperties(PropertyScope scope, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        if (propertiesByName == null) return;
        propertiesByName.forEach((name, value) -> {
            String childPath = path + "." + name;
            AdminPropertyGroupView group = scope.groups().get(name);
            if (group != null) {
                if (value instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) rawMap;
                    checkDeprecatedProperties(PropertyScope.of(group.properties(), group.groups()), nested, childPath, errors);
                }
                return;
            }
            AdminPropertyDefinitionView property = scope.properties().get(name);
            if (property != null && property.usage() == PropertyUsage.DEPRECATED) {
                errors.add(new ValidationError(childPath, PROPERTY_QUOTE_PREFIX + property.name() + "' is deprecated and cannot be set"));
            }
        });
    }

    // Update only -- see MutationRequestProcessor.processItemUpdate's comment on why property:write can't apply to
    // create. Walks the payload's own keys (the way checkDeprecatedProperties does), recursing
    // into groups the same way, since a property's write grant is per-leaf regardless of how deep
    // it's nested.
    void checkCanWriteItemProperties(NtrlocPrincipal principal, UUID itemTypeId, UUID itemId,
                                              Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        if (propertiesByName == null || propertiesByName.isEmpty()) return;
        if (principal.isAdmin()) return;
        var ctx = permissionService.buildContext(principal);
        Set<UUID> itemMarkerIds = permissionService.markerIdsForItem(itemId);
        checkCanWriteProperties(ctx, itemMarkerIds, itemScope(itemTypeId), propertiesByName, path, errors);
    }

    private void checkCanWriteProperties(RequestPermissionContext ctx, Set<UUID> itemMarkerIds, PropertyScope scope,
                                          Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        propertiesByName.forEach((name, value) -> {
            String childPath = path + "." + name;
            AdminPropertyGroupView group = scope.groups().get(name);
            if (group != null) {
                if (value instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) rawMap;
                    checkCanWriteProperties(ctx, itemMarkerIds, PropertyScope.of(group.properties(), group.groups()), nested, childPath, errors);
                }
                return;
            }
            AdminPropertyDefinitionView property = scope.properties().get(name);
            if (property != null && !permissionService.mayWriteProperty(ctx, itemMarkerIds, property.id())) {
                errors.add(new ValidationError(childPath, "Not permitted to write property: " + property.name()));
            }
        });
    }

    // Applies to both create and update, unlike checkCanWriteItemProperties -- a link's two
    // endpoint items already exist (and already carry whatever markers they carry) by the time
    // *any* link mutation runs, including create, since an endpoint is always either an existing
    // item or one created earlier in this same request. Both endpoints' own link_property:write
    // grant are required, same symmetric reasoning as link:create/delete.
    void checkCanWriteLinkProperties(NtrlocPrincipal principal, List<UUID> endpointItemIds, UUID linkTypeId,
                                              Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        if (propertiesByName == null || propertiesByName.isEmpty()) return;
        if (principal.isAdmin()) return;
        var ctx = permissionService.buildContext(principal);
        List<Set<UUID>> endpointMarkerSets = endpointItemIds.stream().map(permissionService::markerIdsForItem).toList();
        checkCanWriteLinkProperties(ctx, endpointMarkerSets, linkScope(linkTypeId), propertiesByName, path, errors);
    }

    private void checkCanWriteLinkProperties(RequestPermissionContext ctx, List<Set<UUID>> endpointMarkerSets, PropertyScope scope,
                                               Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        propertiesByName.forEach((name, value) -> {
            String childPath = path + "." + name;
            AdminPropertyGroupView group = scope.groups().get(name);
            if (group != null) {
                if (value instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) rawMap;
                    checkCanWriteLinkProperties(ctx, endpointMarkerSets, PropertyScope.of(group.properties(), group.groups()), nested, childPath, errors);
                }
                return;
            }
            AdminPropertyDefinitionView property = scope.properties().get(name);
            if (property == null) return;
            boolean allEndpointsGranted = endpointMarkerSets.stream()
                    .allMatch(markerIds -> permissionService.mayWriteLinkProperty(ctx, markerIds, property.id()));
            if (!allEndpointsGranted) {
                errors.add(new ValidationError(childPath, "Not permitted to write link property: " + property.name()));
            }
        });
    }

    private Map<UUID, Object> resolvePropertyIds(Map<String, Object> propertiesByName, PropertyScope scope, String path, List<ValidationError> errors) {
        Map<UUID, Object> byId = new HashMap<>();
        if (propertiesByName == null) return byId;
        propertiesByName.forEach((name, value) -> {
            String childPath = path + "." + name;
            AdminPropertyGroupView group = scope.groups().get(name);
            if (group != null) {
                resolveGroupValue(childPath, group, value, byId, errors);
                return;
            }
            AdminPropertyDefinitionView property = scope.properties().get(name);
            if (property == null) {
                errors.add(new ValidationError(childPath, "Unknown property: " + name));
                return;
            }
            // null is the update-diff "clear this property" sentinel -- never type-checked, but a
            // REQUIRED property can't be cleared this way either (it would leave the item invalid),
            // so this is the one check the sentinel still gets. Applies to a create payload's own
            // explicit null the same as an update's -- both mean the same thing here (see
            // checkRequiredProperties' own comment on why that method only ever covers absence).
            if (value == null) {
                if (property.usage() == PropertyUsage.REQUIRED) {
                    errors.add(new ValidationError(childPath, PROPERTY_QUOTE_PREFIX + property.name() + "' is required and cannot be set to null"));
                    return;
                }
                byId.put(property.id(), null);
                return;
            }
            int before = errors.size();
            validatePropertyValue(childPath, property, value, errors);
            if (errors.size() == before) {
                byId.put(property.id(), value);
            }
        });
        return byId;
    }

    // A group is structure, not a value: its payload is a nested object whose keys are resolved
    // against the group's *own* children, which is what lets two properties named the same thing
    // coexist under different groups -- the JSON nesting scopes the lookup. There is deliberately no
    // way to write to the group itself, so null (which would mean "clear everything under it") is a
    // validation error; clear the leaves you mean to clear.
    private void resolveGroupValue(String path, AdminPropertyGroupView group, Object value,
                                   Map<UUID, Object> byId, List<ValidationError> errors) {
        if (value == null) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + group.name() + "' is a property group and cannot be set to null; clear its properties individually"));
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + group.name() + "' is a property group and expects an object value but got: " + describeValue(value)));
            return;
        }
        // JSON object keys are always strings -- Jackson never produces anything else when binding
        // a JSON object into a Map<String, Object>-typed field, the same assumption the top-level
        // ItemCreateMutation/ItemUpdateMutation properties maps already rely on.
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) rawMap;
        byId.putAll(resolvePropertyIds(nested, PropertyScope.of(group.properties(), group.groups()), path, errors));
    }

    private void validatePropertyValue(String path, AdminPropertyDefinitionView property, Object value, List<ValidationError> errors) {
        if (property.type() == PropertyType.BINARY) {
            validateBinaryValue(path, property, value, errors);
            return;
        }
        if (property.cardinality() == PropertyCardinality.SINGLE) {
            validateScalar(path, property, value, errors);
            return;
        }
        if (!(value instanceof List<?> list)) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' expects a list of values"));
            return;
        }
        if (property.cardinality() == PropertyCardinality.SET) {
            Set<Object> seen = new HashSet<>();
            for (Object element : list) {
                if (!seen.add(element)) {
                    errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' contains a duplicate value: " + element));
                }
            }
        }
        for (int i = 0; i < list.size(); i++) {
            validateScalar(path + "[" + i + "]", property, list.get(i), errors);
        }
    }

    // A binary property's value is the id an earlier POST /api/binary/upload returned, not literal
    // content -- so validation here means confirming that id actually names stored binary content,
    // not type-checking a value the way validateScalar does for every other property type.
    private void validateBinaryValue(String path, AdminPropertyDefinitionView property, Object value, List<ValidationError> errors) {
        if (!(value instanceof String s)) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' expects a binary id but got: " + describeValue(value)));
            return;
        }
        UUID binaryId;
        try {
            binaryId = UUID.fromString(s);
        } catch (IllegalArgumentException _) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' is not a valid binary id: " + s));
            return;
        }
        if (binaryPartitionManager.getBinaryProperty(binaryId).isEmpty()) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' references a binary that does not exist: " + s));
        }
    }

    private void validateScalar(String path, AdminPropertyDefinitionView property, Object value, List<ValidationError> errors) {
        boolean valid = switch (property.type()) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case DECIMAL -> value instanceof Double || value instanceof Integer || value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> value instanceof String s && isValidDate(s);
            case DATETIME -> value instanceof String s && isValidDateTime(s);
            case BINARY -> false; // handled by the caller before reaching here
        };
        if (!valid) {
            errors.add(new ValidationError(path,
                    PROPERTY_QUOTE_PREFIX + property.name() + "' expects a " + property.type() + " value but got: " + describeValue(value)));
            return;
        }
        // Applies identically whether this call is validating a SINGLE property's own value or one
        // element of a LIST/SET property's value list -- both route through this one method.
        if (property.controlledListId() != null && !controlledListManager.containsValue(property.controlledListId(), property.type(), value)) {
            errors.add(new ValidationError(path,
                    PROPERTY_QUOTE_PREFIX + property.name() + "' value is not in its controlled list: " + describeValue(value)));
        }
    }

    private boolean isValidDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (Exception _) {
            return false;
        }
    }

    private boolean isValidDateTime(String s) {
        try {
            OffsetDateTime.parse(s);
            return true;
        } catch (Exception _) {
            try {
                Instant.parse(s);
                return true;
            } catch (Exception _) {
                return false;
            }
        }
    }

    private String describeValue(Object value) {
        return value.getClass().getSimpleName() + " (" + value + ")";
    }
}
