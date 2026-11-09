package org.ntrloc.graph.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.ntrloc.graph.access.AddMarkers;
import org.ntrloc.graph.access.CarrierBlock;
import org.ntrloc.graph.access.Condition;
import org.ntrloc.graph.access.End;
import org.ntrloc.graph.access.Gate;
import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.Route;
import org.ntrloc.graph.access.Self;
import org.ntrloc.graph.access.SignalBlock;
import org.ntrloc.graph.access.Start;
import org.ntrloc.graph.access.StepToProperty;
import org.ntrloc.graph.access.StringEqualsPredicate;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.mutation.ExistingItemReference;
import org.ntrloc.graph.mutation.ItemCreateMutation;
import org.ntrloc.graph.mutation.ItemMutation;
import org.ntrloc.graph.mutation.LinkCreateMutation;
import org.ntrloc.graph.mutation.LinkEndpointReference;
import org.ntrloc.graph.mutation.LinkMutation;
import org.ntrloc.graph.mutation.MutationRequest;
import org.ntrloc.graph.mutation.NewItemReference;

import tools.jackson.databind.JsonNode;

// Seeds the benchmark's dataset entirely through ntrloc's HTTP APIs, as an admin:
//
// - BenchAsset, with subtypes BenchProduct and BenchPart (for cross-type projection), and
//   BenchSupplier, linked to products (products see "suppliers", suppliers see "products");
// - four region markers, applied by each concrete type's marker decision pipeline from the item's
//   region, so every item carries exactly one;
// - a non-admin in a "bench-readers" group that may read items marked North (and their properties
//   and links), so a quarter of the data is visible to them -- the permission-filtered path.
//
// Values come from a fixed seed, so every run builds the same data.
final class ProjectionBenchmarkDataset {

    static final String ASSET = "BenchAsset";
    static final String PRODUCT = "BenchProduct";
    static final String PART = "BenchPart";
    static final String SUPPLIER = "BenchSupplier";
    static final String SUPPLIERS = "suppliers";
    static final String PRODUCTS = "products";
    static final List<String> REGIONS = List.of("North", "South", "East", "West");
    static final String VISIBLE_REGION = "North";

    private static final int BATCH_SIZE = 250;

    record Size(int products, int parts, int suppliers) {}

    // What the scenarios need to know about what was seeded.
    record Seeded(Size size, String userToken, UUID visibleProductId, long seedMillis) {

        long visibleProducts() {
            return regionCount(size.products());
        }

        long visibleAssets() {
            return regionCount(size.products()) + regionCount(size.parts());
        }

        // Items are assigned regions round-robin, so the visible region holds every fourth one.
        private static long regionCount(int total) {
            return (total + REGIONS.size() - 1) / REGIONS.size();
        }
    }

    private final BenchmarkHttp http;
    private final String adminToken;
    private final Random random = new Random(42);

    ProjectionBenchmarkDataset(BenchmarkHttp http, String adminToken) {
        this.http = http;
        this.adminToken = adminToken;
    }

    Seeded seed(Size size) {
        long started = System.nanoTime();
        createSchema();
        JsonNode schema = http.get(adminToken, "/api/admin/schema");
        Map<String, UUID> markers = createRegionMarkers(itemTypeId(schema, ASSET));
        UUID regionPropertyId = propertyId(schema, ASSET, "region");
        for (String type : List.of(PRODUCT, PART, SUPPLIER)) {
            setRegionPipeline(itemTypeId(schema, type), regionPropertyId(schema, type, regionPropertyId), markers);
        }
        String userToken = createReader(schema, markers.get(VISIBLE_REGION));

        List<UUID> suppliers = createItems(SUPPLIER, size.suppliers(), null);
        List<UUID> products = createItems(PRODUCT, size.products(), suppliers);
        createItems(PART, size.parts(), null);
        long seedMillis = (System.nanoTime() - started) / 1_000_000;
        return new Seeded(size, userToken, products.getFirst(), seedMillis);
    }

    private void createSchema() {
        List<CreatePropertyDefinitionMutation> assetProperties = List.of(
                property("name", PropertyType.STRING, false),
                property("sku", PropertyType.STRING, false),
                property("region", PropertyType.STRING, true),
                property("price", PropertyType.DECIMAL, true),
                property("quantity", PropertyType.INTEGER, true));
        http.postList(adminToken, "/api/admin/schema/mutations", List.<DefinitionMutation>of(
                new CreateItemDefinitionMutation(ASSET, "Benchmark supertype", assetProperties, null, false, null),
                new CreateItemDefinitionMutation(SUPPLIER, "Benchmark supplier", List.of(
                        property("name", PropertyType.STRING, false),
                        property("region", PropertyType.STRING, true)), null, false, null)), DefinitionMutation.class);

        UUID assetId = itemTypeId(http.get(adminToken, "/api/admin/schema"), ASSET);
        http.postList(adminToken, "/api/admin/schema/mutations", List.<DefinitionMutation>of(
                new CreateItemDefinitionMutation(PRODUCT, "Benchmark product", List.of(), assetId, false, null),
                new CreateItemDefinitionMutation(PART, "Benchmark part", List.of(), assetId, false, null)), DefinitionMutation.class);

        JsonNode schema = http.get(adminToken, "/api/admin/schema");
        http.postList(adminToken, "/api/admin/schema/mutations", List.<DefinitionMutation>of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(itemTypeId(schema, PRODUCT), SUPPLIERS, "Benchmark", 0, null),
                new CreatePerspectiveDefinitionMutation(itemTypeId(schema, SUPPLIER), PRODUCTS, "Benchmark", 0, null)))),
                DefinitionMutation.class);
    }

    private static CreatePropertyDefinitionMutation property(String name, PropertyType type, boolean facetable) {
        return new CreatePropertyDefinitionMutation(name, "Benchmark", type, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, facetable);
    }

    private Map<String, UUID> createRegionMarkers(UUID scopeItemTypeId) {
        Map<String, UUID> markers = new LinkedHashMap<>();
        for (String region : REGIONS) {
            JsonNode marker = http.post(adminToken, "/api/admin/markers", Map.of(
                    "name", "Bench " + region, "description", "Benchmark region marker",
                    "scopeKind", "ITEM_TYPE", "scopeId", scopeItemTypeId));
            markers.put(region, UUID.fromString(marker.get("id").asString()));
        }
        return markers;
    }

    // A subtype's region property is its supertype's; a supplier has its own.
    private static UUID regionPropertyId(JsonNode schema, String type, UUID assetRegionPropertyId) {
        return type.equals(SUPPLIER) ? propertyId(schema, SUPPLIER, "region") : assetRegionPropertyId;
    }

    // Replaces the type's auto-created pipeline with one that adds the marker for the item's region.
    private void setRegionPipeline(UUID itemTypeId, UUID regionPropertyId, Map<String, UUID> markers) {
        List<SignalBlock> conditions = new ArrayList<>();
        List<CarrierBlock> adds = new ArrayList<>();
        for (var marker : markers.entrySet()) {
            var condition = new Condition(UUID.randomUUID(), new StepToProperty(regionPropertyId, false, new Self()),
                    new StringEqualsPredicate(null, marker.getKey()));
            conditions.add(condition);
            adds.add(new AddMarkers(UUID.randomUUID(), Gate.of(condition.id()), List.of(marker.getValue())));
        }
        var start = new Start(UUID.randomUUID());
        var end = new End(UUID.randomUUID());
        List<Route> routes = new ArrayList<>();
        UUID previous = start.id();
        for (CarrierBlock add : adds) {
            routes.add(new Route(previous, List.of(add.id())));
            previous = add.id();
        }
        routes.add(new Route(previous, List.of(end.id())));
        var pipeline = new Pipeline(UUID.randomUUID(), conditions, adds, start, end, routes);

        JsonNode existing = http.get(adminToken, "/api/admin/marker-decision-pipelines/by-item-type/" + itemTypeId);
        Map<String, Object> update = new HashMap<>();
        update.put("pipeline", pipeline);
        update.put("enabled", true);
        update.put("layout", null);
        http.put(adminToken, "/api/admin/marker-decision-pipelines/" + existing.get("id").asString(), update);
    }

    // A non-admin who can read North items, their properties and their links -- and nothing else.
    private String createReader(JsonNode schema, UUID visibleMarkerId) {
        JsonNode everyone = null;
        for (JsonNode group : http.get(adminToken, "/api/admin/user-groups")) {
            if (group.get("name").asString().equals("everyone")) {
                everyone = group;
            }
        }
        if (everyone == null) {
            throw new IllegalStateException("No 'everyone' group");
        }
        String groupId = http.post(adminToken, "/api/admin/user-groups",
                Map.of("name", "bench-readers", "parentUserGroupId", everyone.get("id").asString())).get("id").asString();
        String userId = http.post(adminToken, "/api/admin/users", Map.of(
                "displayName", "Bench Reader", "email", "bench-reader@example.com",
                "password", "bench-" + UUID.randomUUID(), "role", "USER")).get("id").asString();
        http.post(adminToken, "/api/admin/user-groups/" + groupId + "/members", Map.of("userId", userId));

        for (String type : List.of(ASSET, PRODUCT, PART, SUPPLIER)) {
            http.post(adminToken, "/api/admin/user-groups/" + groupId + "/permissions",
                    Map.of("itemTypeId", itemTypeId(schema, type), "operation", "item-type:read"));
        }
        String markerPath = "/api/admin/user-groups/" + groupId + "/markers/" + visibleMarkerId;
        http.put(adminToken, markerPath + "/item-permissions", Map.of("canRead", true, "canDelete", false));
        for (String type : List.of(ASSET, SUPPLIER)) {
            for (JsonNode property : itemType(schema, type).get("properties")) {
                http.put(adminToken, markerPath + "/properties/" + property.get("id").asString(),
                        Map.of("canRead", true, "canWrite", false));
            }
        }
        for (UUID perspectiveId : List.of(perspectiveId(schema, PRODUCT, SUPPLIERS), perspectiveId(schema, SUPPLIER, PRODUCTS))) {
            http.put(adminToken, markerPath + "/link-perspectives/" + perspectiveId,
                    Map.of("canCreate", false, "canRead", true, "canDelete", false));
        }
        return http.post(adminToken, "/api/admin/users/" + userId + "/tokens",
                Map.of("name", "projection-benchmark")).get("token").asString();
    }

    // Creates `count` items in batches; products each link to a supplier. Returns ids in creation
    // order, so item i has region REGIONS[i % 4].
    private List<UUID> createItems(String type, int count, List<UUID> suppliers) {
        List<UUID> ids = new ArrayList<>(count);
        for (int start = 0; start < count; start += BATCH_SIZE) {
            List<ItemMutation> items = new ArrayList<>();
            List<LinkMutation> links = new ArrayList<>();
            for (int i = start; i < Math.min(start + BATCH_SIZE, count); i++) {
                String refId = "i" + i;
                items.add(new ItemCreateMutation(refId, type, properties(type, i)));
                if (suppliers != null) {
                    links.add(new LinkCreateMutation(
                            new LinkEndpointReference(SUPPLIERS, new NewItemReference(refId)),
                            new LinkEndpointReference(PRODUCTS, new ExistingItemReference(suppliers.get(i % suppliers.size()))),
                            Map.of()));
                }
            }
            JsonNode response = http.post(adminToken, "/api/mutation", new MutationRequest(items, links));
            for (JsonNode item : response.get("items")) {
                ids.add(UUID.fromString(item.get("itemId").asString()));
            }
        }
        return ids;
    }

    private Map<String, Object> properties(String type, int i) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", "%s %06d".formatted(type, i));
        properties.put("region", REGIONS.get(i % REGIONS.size()));
        if (!type.equals(SUPPLIER)) {
            properties.put("sku", "SKU-" + Integer.toHexString(random.nextInt()));
            properties.put("price", BigDecimal.valueOf(1 + random.nextDouble() * 999).setScale(2, RoundingMode.HALF_UP));
            properties.put("quantity", random.nextInt(1000));
        }
        return properties;
    }

    private static JsonNode itemType(JsonNode schema, String name) {
        for (JsonNode item : schema.get("items")) {
            if (item.get("name").asString().equals(name)) {
                return item;
            }
        }
        throw new IllegalStateException("No item type " + name);
    }

    private static UUID itemTypeId(JsonNode schema, String name) {
        return UUID.fromString(itemType(schema, name).get("id").asString());
    }

    private static UUID propertyId(JsonNode schema, String itemType, String property) {
        for (JsonNode p : itemType(schema, itemType).get("properties")) {
            if (p.get("name").asString().equals(property)) {
                return UUID.fromString(p.get("id").asString());
            }
        }
        throw new IllegalStateException("No property " + itemType + "." + property);
    }

    private static UUID perspectiveId(JsonNode schema, String itemType, String perspective) {
        return UUID.fromString(itemType(schema, itemType).get("links").get(perspective).get(0).get("id").asString());
    }
}
