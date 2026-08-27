package org.ntrloc.graph.db.mutation;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.ObjectAdminPropertyDefinitionView;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Turns a client-facing MutationRequest -- which speaks only in names (item type, property,
// perspective), never internal schema ids -- into concrete LedgerEntry objects (which speak
// only in ids), resolving refIds and all name lookups along the way. Drives the result through
// the coordinator as one atomic unit.
//
// Validates the whole request before touching anything: every mutation is checked and every
// resolvable reference is resolved in one pass, accumulating a ValidationError per bad reference
// rather than failing on the first one. If anything failed, MutationValidationException carries
// the complete list and the coordinator is never called -- nothing has been written yet at that
// point (schema lookups are all against SchemaManager's in-memory cache), so there's nothing to
// roll back. Permission checks are a separate, not-yet-built component (Section 12).
@Component
public class MutationRequestProcessor {

    private static final String PROPERTIES_PATH_SUFFIX = ".properties";
    private static final String PROPERTY_QUOTE_PREFIX = "Property '";

    private final LedgerRegisterCoordinator coordinator;
    private final RegisterPartitionManager registerPartitionManager;
    private final SchemaManager schemaManager;

    public MutationRequestProcessor(LedgerRegisterCoordinator coordinator,
                                     RegisterPartitionManager registerPartitionManager,
                                     SchemaManager schemaManager) {
        this.coordinator = coordinator;
        this.registerPartitionManager = registerPartitionManager;
        this.schemaManager = schemaManager;
    }

    @Transactional
    public MutationResponse process(MutationRequest request, @Nullable NtrlocPrincipal principal) {
        List<ValidationError> errors = new ArrayList<>();
        Map<String, UUID> refIdToItemId = new HashMap<>();
        Map<String, UUID> refIdToItemTypeId = new HashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();
        List<ItemMutationResult> itemResults = new ArrayList<>();
        List<LinkMutationResult> linkResults = new ArrayList<>();

        List<ItemMutation> items = request.items() == null ? List.of() : request.items();
        List<LinkMutation> links = request.links() == null ? List.of() : request.links();

        // Items before links: link endpoint resolution needs new (refId) references already
        // assigned real ids and known types.
        for (int i = 0; i < items.size(); i++) {
            String path = "items[%d]".formatted(i);
            ItemMutation item = items.get(i);
            if (item instanceof ItemCreateMutation m) {
                processItemCreate(m, path, refIdToItemId, refIdToItemTypeId, entries, itemResults, errors);
            } else if (item instanceof ItemUpdateMutation m) {
                processItemUpdate(m, path, entries, itemResults, errors);
            } else if (item instanceof ItemDeleteMutation m) {
                entries.add(new ItemDeleteEntry(m.itemId()));
                itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.DELETE));
            }
        }

        for (int i = 0; i < links.size(); i++) {
            String path = "links[%d]".formatted(i);
            LinkMutation link = links.get(i);
            if (link instanceof LinkCreateMutation m) {
                processLinkCreate(m, path, refIdToItemId, refIdToItemTypeId, entries, linkResults, errors);
            } else if (link instanceof LinkUpdateMutation m) {
                processLinkUpdate(m, path, entries, linkResults, errors);
            } else if (link instanceof LinkDeleteMutation m) {
                entries.add(new LinkDeleteEntry(m.linkId()));
                linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.DELETE));
            }
        }

        if (!errors.isEmpty()) {
            throw new MutationValidationException(errors);
        }

        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        // principal is @Nullable, same as it is on EntityManager.mutate() -- unlike project()'s
        // principal (a hard permission-check requirement), this one is attribution-only
        // (ledger_entry.actor_external_id is nullable by design): an unresolvable/absent principal
        // is a real, displayable state ("Edited by" blank), not a reason to refuse the mutation.
        String actorExternalId = principal == null ? null : principal.externalId();
        coordinator.prepare(entries, transactionId, actorExternalId);
        coordinator.commit(transactionId, commitId);

        return new MutationResponse(itemResults, linkResults);
    }

    private void processItemCreate(ItemCreateMutation m, String path, Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                    List<LedgerEntry> entries, List<ItemMutationResult> itemResults, List<ValidationError> errors) {
        Optional<UUID> itemTypeId = findItemTypeIdByName(m.itemTypeName());
        if (itemTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".itemTypeName", "Unknown item type: " + m.itemTypeName()));
            return;
        }
        if (isAbstractItemType(itemTypeId.get())) {
            errors.add(new ValidationError(path + ".itemTypeName", "Cannot create an instance of abstract item type: " + m.itemTypeName()));
            return;
        }
        UUID itemId = UUID.randomUUID();
        if (m.refId() != null) {
            refIdToItemId.put(m.refId(), itemId);
            refIdToItemTypeId.put(m.refId(), itemTypeId.get());
        }
        Map<UUID, Object> properties = resolveItemPropertyIds(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        entries.add(new ItemCreateEntry(itemId, itemTypeId.get(), properties, Map.of(), Set.of()));
        itemResults.add(new ItemMutationResult(m.refId(), itemId, MutationOperation.CREATE));
    }

    private void processItemUpdate(ItemUpdateMutation m, String path, List<LedgerEntry> entries,
                                    List<ItemMutationResult> itemResults, List<ValidationError> errors) {
        Optional<UUID> itemTypeId = registerPartitionManager.findItemTypeId(m.itemId());
        if (itemTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".itemId", "Unknown item: " + m.itemId()));
            return;
        }
        Map<UUID, Object> properties = resolveItemPropertyIds(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        entries.add(new ItemUpdateEntry(m.itemId(), properties, Map.of(), Set.of(), Set.of()));
        itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.UPDATE));
    }

    private void processLinkCreate(LinkCreateMutation m, String path, Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                    List<LedgerEntry> entries, List<LinkMutationResult> linkResults, List<ValidationError> errors) {
        Optional<ResolvedEndpoint> a = resolveEndpoint(m.firstItem(), path + ".firstItem", refIdToItemId, refIdToItemTypeId, errors);
        Optional<ResolvedEndpoint> b = resolveEndpoint(m.secondItem(), path + ".secondItem", refIdToItemId, refIdToItemTypeId, errors);
        if (a.isEmpty() || b.isEmpty()) return;

        Optional<UUID> linkTypeId = resolveLinkTypeId(a.get(), b.get(), path, errors);
        if (linkTypeId.isEmpty()) return;

        UUID linkId = UUID.randomUUID();
        LinkEndpoint endpointA = new LinkEndpoint(a.get().perspectiveIdFor(linkTypeId.get()), a.get().itemId());
        LinkEndpoint endpointB = new LinkEndpoint(b.get().perspectiveIdFor(linkTypeId.get()), b.get().itemId());
        Map<UUID, Object> properties = resolveLinkPropertyIds(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        entries.add(new LinkCreateEntry(linkId, linkTypeId.get(), endpointA, endpointB, properties));
        linkResults.add(new LinkMutationResult(linkId, MutationOperation.CREATE));
    }

    private void processLinkUpdate(LinkUpdateMutation m, String path, List<LedgerEntry> entries,
                                    List<LinkMutationResult> linkResults, List<ValidationError> errors) {
        Optional<UUID> linkTypeId = registerPartitionManager.findLinkTypeId(m.linkId());
        if (linkTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".linkId", "Unknown link: " + m.linkId()));
            return;
        }
        Map<UUID, Object> properties = resolveLinkPropertyIds(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        entries.add(new LinkUpdateEntry(m.linkId(), properties));
        linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.UPDATE));
    }

    // Mirrors EntityManagerImpl.resolveItemTypeId -- the one existing name->id precedent in
    // this codebase, on the read side.
    private Optional<UUID> findItemTypeIdByName(String itemTypeName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(itemTypeName))
                .map(item -> item.id())
                .findFirst();
    }

    private record ResolvedEndpoint(UUID itemId, String perspectiveName, List<AdminItemLinkPerspectiveView> candidates) {
        Set<UUID> candidateLinkTypeIds() {
            return candidates.stream().map(AdminItemLinkPerspectiveView::linkId).collect(Collectors.toSet());
        }

        UUID perspectiveIdFor(UUID linkTypeId) {
            return candidates.stream()
                    .filter(c -> c.linkId().equals(linkTypeId))
                    .map(AdminItemLinkPerspectiveView::id)
                    .findFirst()
                    .orElseThrow();
        }
    }

    private Optional<ResolvedEndpoint> resolveEndpoint(LinkEndpointReference ref, String path,
                                                         Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                                         List<ValidationError> errors) {
        UUID itemId;
        UUID itemTypeId;
        if (ref.item() instanceof NewItemReference r) {
            itemId = refIdToItemId.get(r.refId());
            itemTypeId = refIdToItemTypeId.get(r.refId());
            if (itemId == null) {
                errors.add(new ValidationError(path + ".item.refId", "Unknown refId: " + r.refId()));
                return Optional.empty();
            }
        } else if (ref.item() instanceof ExistingItemReference r) {
            Optional<UUID> resolved = registerPartitionManager.findItemTypeId(r.itemId());
            if (resolved.isEmpty()) {
                errors.add(new ValidationError(path + ".item.itemId", "Unknown item: " + r.itemId()));
                return Optional.empty();
            }
            itemId = r.itemId();
            itemTypeId = resolved.get();
        } else {
            throw new IllegalArgumentException("Unsupported item reference: " + ref.item().getClass().getSimpleName());
        }

        List<AdminItemLinkPerspectiveView> candidates = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.links().getOrDefault(ref.perspectiveName(), List.of()))
                .orElse(List.of());
        if (candidates.isEmpty()) {
            errors.add(new ValidationError(path + ".perspectiveName", "Unknown perspective: " + ref.perspectiveName()));
            return Optional.empty();
        }
        return Optional.of(new ResolvedEndpoint(itemId, ref.perspectiveName(), candidates));
    }

    // A perspective name isn't unique per item type (the schema allows the same name across
    // multiple distinct link definitions), so the concrete link type is whichever one both
    // endpoints' perspective candidates agree on.
    private Optional<UUID> resolveLinkTypeId(ResolvedEndpoint a, ResolvedEndpoint b, String path, List<ValidationError> errors) {
        Set<UUID> common = new HashSet<>(a.candidateLinkTypeIds());
        common.retainAll(b.candidateLinkTypeIds());
        if (common.isEmpty()) {
            errors.add(new ValidationError(path,
                    "No link connects perspective '%s' to perspective '%s'".formatted(a.perspectiveName(), b.perspectiveName())));
            return Optional.empty();
        }
        if (common.size() > 1) {
            errors.add(new ValidationError(path,
                    "Ambiguous link: multiple link types connect perspective '%s' to perspective '%s'".formatted(a.perspectiveName(), b.perspectiveName())));
            return Optional.empty();
        }
        return Optional.of(common.iterator().next());
    }

    // An abstract item type exists only to be extended -- items must be created as one of its
    // concrete subtypes instead. Checked here, not in SchemaMutationValidation, since this is an
    // item-instance write-path concern, not a schema-mutation one (see this class's ValidationError
    // convention vs. that class's IllegalArgumentException one).
    private boolean isAbstractItemType(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::abstractType)
                .orElse(false);
    }

    private Map<UUID, Object> resolveItemPropertyIds(UUID itemTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        Map<String, AdminPropertyDefinitionView> nameToProperty = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::name, p -> p)))
                .orElse(Map.of());
        return resolvePropertyIds(propertiesByName, nameToProperty, path, errors);
    }

    private Map<UUID, Object> resolveLinkPropertyIds(UUID linkTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        Map<String, AdminPropertyDefinitionView> nameToProperty = schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> link.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::name, p -> p)))
                .orElse(Map.of());
        return resolvePropertyIds(propertiesByName, nameToProperty, path, errors);
    }

    private Map<UUID, Object> resolvePropertyIds(Map<String, Object> propertiesByName, Map<String, AdminPropertyDefinitionView> nameToProperty,
                                                  String path, List<ValidationError> errors) {
        Map<UUID, Object> byId = new HashMap<>();
        if (propertiesByName == null) return byId;
        propertiesByName.forEach((name, value) -> {
            AdminPropertyDefinitionView property = nameToProperty.get(name);
            if (property == null) {
                errors.add(new ValidationError(path + "." + name, "Unknown property: " + name));
                return;
            }
            // OBJECT properties are intercepted here, one level above validatePropertyValue --
            // unlike every other type, resolving one doesn't produce a single id->value entry, it
            // recurses and flattens into potentially many leaf entries (or, for null, an entire
            // subtree of them), so it can't share the single-put path below.
            if (property instanceof ObjectAdminPropertyDefinitionView objectProperty) {
                resolveObjectPropertyValue(path + "." + name, objectProperty, value, byId, errors);
                return;
            }
            // null is the update-diff "clear this property" sentinel -- never type-checked.
            if (value == null) {
                byId.put(property.id(), null);
                return;
            }
            int before = errors.size();
            validatePropertyValue(path + "." + name, property, value, errors);
            if (errors.size() == before) {
                byId.put(property.id(), value);
            }
        });
        return byId;
    }

    // Recurses into an OBJECT property's nested payload, resolving each child name against the
    // property's *own* children -- not the container's list -- which is what lets two properties
    // named the same thing coexist under different object properties: the JSON nesting itself
    // scopes the lookup, same as any nested JSON disambiguates same-named keys by which object
    // they're inside. A bare `null` wipes the entire subtree: every leaf currently under this
    // object property per the *live* schema (not just whatever the client happened to list) gets
    // cleared, since a client can't be expected to enumerate a container's full current shape.
    private void resolveObjectPropertyValue(String path, ObjectAdminPropertyDefinitionView objectProperty, Object value,
                                             Map<UUID, Object> byId, List<ValidationError> errors) {
        if (value == null) {
            nullAllLeaves(objectProperty.properties(), byId);
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + objectProperty.name() + "' expects an object value but got: " + describeValue(value)));
            return;
        }
        Map<String, AdminPropertyDefinitionView> childNameToProperty = objectProperty.properties().stream()
                .collect(Collectors.toMap(AdminPropertyDefinitionView::name, p -> p));
        // JSON object keys are always strings -- Jackson never produces anything else when binding
        // a JSON object into a Map<String, Object>-typed field, the same assumption the top-level
        // ItemCreateMutation/ItemUpdateMutation properties maps already rely on.
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedProperties = (Map<String, Object>) rawMap;
        byId.putAll(resolvePropertyIds(nestedProperties, childNameToProperty, path, errors));
    }

    private void nullAllLeaves(List<AdminPropertyDefinitionView> properties, Map<UUID, Object> byId) {
        for (AdminPropertyDefinitionView p : properties) {
            if (p instanceof ObjectAdminPropertyDefinitionView o) {
                nullAllLeaves(o.properties(), byId);
            } else {
                byId.put(p.id(), null);
            }
        }
    }

    private void validatePropertyValue(String path, AdminPropertyDefinitionView property, Object value, List<ValidationError> errors) {
        if (property.type() == PropertyType.BINARY) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' is binary-typed; binary properties cannot be set via mutation"));
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

    private void validateScalar(String path, AdminPropertyDefinitionView property, Object value, List<ValidationError> errors) {
        boolean valid = switch (property.type()) {
            case STRING -> value instanceof String;
            case INT -> value instanceof Integer;
            case LONG -> value instanceof Integer || value instanceof Long;
            case DOUBLE -> value instanceof Double || value instanceof Integer || value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
            case OBJECT -> false; // handled by resolvePropertyIds/resolveObjectPropertyValue before reaching here
            case DATE -> value instanceof String s && isValidDate(s);
            case DATETIME -> value instanceof String s && isValidDateTime(s);
            case BINARY -> false; // handled by the caller before reaching here
        };
        if (!valid) {
            errors.add(new ValidationError(path,
                    PROPERTY_QUOTE_PREFIX + property.name() + "' expects a " + property.type() + " value but got: " + describeValue(value)));
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
