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

import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.springframework.stereotype.Component;

// Resolves a mutation payload's property names to ids and checks each value against its property's
// type, cardinality, controlled list, or (for BINARY) stored content. Problems are added to the
// caller's error list, in MutationRequestProcessor's accumulate-everything style.
@Component
class PropertyValueValidator {

    private static final String PROPERTY_QUOTE_PREFIX = "Property '";

    private final BinaryPartitionManager binaryPartitionManager;
    private final ControlledListManager controlledListManager;

    PropertyValueValidator(BinaryPartitionManager binaryPartitionManager, ControlledListManager controlledListManager) {
        this.binaryPartitionManager = binaryPartitionManager;
        this.controlledListManager = controlledListManager;
    }

    Map<UUID, Object> resolvePropertyIds(Map<String, Object> propertiesByName, PropertyScope scope, String path, List<ValidationError> errors) {
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
        } catch (IllegalArgumentException e) {
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
            case INT -> value instanceof Integer;
            case LONG -> value instanceof Integer || value instanceof Long;
            case DOUBLE -> value instanceof Double || value instanceof Integer || value instanceof Long;
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
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isValidDateTime(String s) {
        try {
            OffsetDateTime.parse(s);
            return true;
        } catch (Exception e) {
            try {
                Instant.parse(s);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private String describeValue(Object value) {
        return value.getClass().getSimpleName() + " (" + value + ")";
    }
}
