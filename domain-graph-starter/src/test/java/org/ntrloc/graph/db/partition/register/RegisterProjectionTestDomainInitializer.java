package org.ntrloc.graph.db.partition.register;

import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Dedicated fixture for RegisterPartitionManagerProjectionIntegrationTest -- kept separate from
// CoordinatorTestDomainInitializer (used by the ledger/mutation tests) so this one can carry the
// property variety projection/faceting needs (a boolean, a controlled-list-backed string, a state
// machine) without touching a fixture other test classes already depend on.
@Component
public class RegisterProjectionTestDomainInitializer implements DomainInitializer, ApplicationRunner {

    public static final String AVAILABILITY_MACHINE = "Availability";
    public static final String AVAILABLE = "Available";
    public static final String OUT_OF_STOCK = "OutOfStock";
    public static final String DISCONTINUED = "Discontinued";

    private final SchemaManager schemaManager;
    private final ControlledListManager controlledListManager;

    private UUID bookTypeId;
    private UUID authorTypeId;
    private UUID titlePropertyId;
    private UUID pageCountPropertyId;
    private UUID inStockPropertyId;
    private UUID genrePropertyId;
    private UUID authorsPerspectiveId;

    public RegisterProjectionTestDomainInitializer(SchemaManager schemaManager, ControlledListManager controlledListManager) {
        this.schemaManager = schemaManager;
        this.controlledListManager = controlledListManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        initSchema(schemaManager, controlledListManager);
    }

    @Override
    public void initSchema(SchemaManager schemaManager, ControlledListManager controlledListManager) {
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "RegisterProjectionTestBook", "RegisterPartitionManager projection/facet test fixture",
                List.of(
                        property("title", PropertyType.STRING, PropertyCardinality.SINGLE),
                        property("pageCount", PropertyType.INT, PropertyCardinality.SINGLE),
                        property("inStock", PropertyType.BOOLEAN, PropertyCardinality.SINGLE),
                        property("genre", PropertyType.STRING, PropertyCardinality.SINGLE),
                        // Not a domain property -- exists purely so the test class can scope every
                        // query down to one test method's own rows despite every test sharing the
                        // same singleton Postgres container and register table (AbstractIntegrationTest's
                        // own class comment on why it's a singleton, not per-class). No controlled
                        // list, so it's also not facetable -- doesn't show up in the "every
                        // facetable property" auto-populate test.
                        property("testMarker", PropertyType.STRING, PropertyCardinality.SINGLE)))));

        AdminItemDefinitionView book = findItem("RegisterProjectionTestBook");
        bookTypeId = book.id();
        titlePropertyId = findProperty(book.properties(), "title");
        pageCountPropertyId = findProperty(book.properties(), "pageCount");
        inStockPropertyId = findProperty(book.properties(), "inStock");
        genrePropertyId = findProperty(book.properties(), "genre");

        // genre is controlled-list-backed specifically so it exercises the STRING branch of
        // isTermsFacetable (which requires a controlled list); inStock exercises the BOOLEAN
        // branch (facetable unconditionally) -- between the two, both facetable-property code
        // paths are covered, not just one.
        var genreList = controlledListManager.createList("RegisterProjectionTestGenre", PropertyType.STRING);
        controlledListManager.setPropertyControlledList(genrePropertyId, genreList.id());
        controlledListManager.addValue(genreList.id(), "Fiction", "Fiction", 0);
        controlledListManager.addValue(genreList.id(), "Reference", "Reference", 1);

        initStateMachine(schemaManager, bookTypeId, AVAILABILITY_MACHINE,
                List.of(
                        new StateDefinition(AVAILABLE, true),
                        new StateDefinition(OUT_OF_STOCK, false),
                        new StateDefinition(DISCONTINUED, false)),
                List.of(
                        new TransitionDefinition(AVAILABLE, OUT_OF_STOCK, "MarkOutOfStock"),
                        new TransitionDefinition(OUT_OF_STOCK, AVAILABLE, "Restock"),
                        new TransitionDefinition(OUT_OF_STOCK, DISCONTINUED, "Discontinue")));

        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "RegisterProjectionTestAuthor", "RegisterPartitionManager projection/facet test fixture",
                List.of(property("name", PropertyType.STRING, PropertyCardinality.SINGLE)))));
        authorTypeId = findItem("RegisterProjectionTestAuthor").id();

        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(
                List.of(),
                List.of(
                        new CreatePerspectiveDefinitionMutation(bookTypeId, "authors", "desc", 0, null),
                        new CreatePerspectiveDefinitionMutation(authorTypeId, "books", "desc", 0, null)))));

        authorsPerspectiveId = findItem("RegisterProjectionTestBook").links().get("authors").get(0).id();
    }

    private CreatePropertyDefinitionMutation property(String name, PropertyType type, PropertyCardinality cardinality) {
        return new CreatePropertyDefinitionMutation(name, "Register projection test fixture", type, cardinality, PropertyUsage.OPTIONAL);
    }

    private AdminItemDefinitionView findItem(String name) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private UUID findProperty(List<AdminPropertyDefinitionView> properties, String name) {
        return properties.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .map(AdminPropertyDefinitionView::id)
                .orElseThrow();
    }

    public UUID bookTypeId() {
        return bookTypeId;
    }

    public UUID authorTypeId() {
        return authorTypeId;
    }

    public UUID titlePropertyId() {
        return titlePropertyId;
    }

    public UUID pageCountPropertyId() {
        return pageCountPropertyId;
    }

    public UUID inStockPropertyId() {
        return inStockPropertyId;
    }

    public UUID genrePropertyId() {
        return genrePropertyId;
    }

    public UUID authorsPerspectiveId() {
        return authorsPerspectiveId;
    }
}
