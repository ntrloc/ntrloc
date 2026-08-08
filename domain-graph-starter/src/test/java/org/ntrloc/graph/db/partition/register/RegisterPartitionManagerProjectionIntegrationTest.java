package org.ntrloc.graph.db.partition.register;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.mutation.ExistingItemReference;
import org.ntrloc.graph.db.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.mutation.LinkEndpointReference;
import org.ntrloc.graph.db.mutation.MutationRequest;
import org.ntrloc.graph.db.mutation.MutationResponse;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteItemDefinitionMutation;
import org.ntrloc.graph.db.projection.AndPredicate;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.DateRangeFacetFilter;
import org.ntrloc.graph.db.projection.FacetBucket;
import org.ntrloc.graph.db.projection.NotPredicate;
import org.ntrloc.graph.db.projection.Operator;
import org.ntrloc.graph.db.projection.OrPredicate;
import org.ntrloc.graph.db.projection.Predicate;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.ntrloc.graph.db.projection.PropertyExistencePredicate;
import org.ntrloc.graph.db.projection.PropertyValuePredicate;
import org.ntrloc.graph.db.projection.RangeFacetFilter;
import org.ntrloc.graph.db.projection.StateValuePredicate;
import org.ntrloc.graph.db.projection.TermsFacetFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// Covers RegisterPartitionManager.project(...) and its supporting machinery (predicate
// translation, faceting, sorting/pagination, link expansion, setItemState, schema-change table
// management) -- the read/query side of the class. The write side (create/update/delete via the
// ledger/coordinator) is already exercised by MutationControllerIntegrationTest and
// LedgerRegisterCoordinatorIntegrationTest; this file deliberately doesn't repeat that.
class RegisterPartitionManagerProjectionIntegrationTest extends AbstractIntegrationTest {

    private static final String BOOK_TYPE = "RegisterProjectionTestBook";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private RegisterProjectionTestDomainInitializer fixture;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private JdbcClient jdbcClient;

    // Every book this test class creates gets this value stamped onto testMarker
    // (RegisterProjectionTestDomainInitializer's own comment on why that property exists), and
    // project() below always ANDs a "testMarker = this test's marker" predicate into whatever
    // filter the test asked for -- so a facet/sort/pagination query run by one test method can
    // never see another method's leftover rows, without every test having to remember to scope
    // itself manually. Tests that instead look up a single, specific item by id (setItemState,
    // projectOne) don't need this -- an exact id can't collide with another test's rows either way.
    private String marker;

    @BeforeEach
    void authenticateAsStandInUserAndGenerateUniqueMarker() {
        // Same stand-in mechanism as MutationControllerIntegrationTest -- see that class's own
        // comment on why "root" specifically.
        webTestClient = webTestClient.mutate().defaultHeader("X-Ntrloc-User", "root").build();
        marker = UUID.randomUUID().toString();
    }

    private UUID createBook(String title, Integer pageCount, Boolean inStock, String genre) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("testMarker", marker);
        if (title != null) properties.put("title", title);
        if (pageCount != null) properties.put("pageCount", pageCount);
        if (inStock != null) properties.put("inStock", inStock);
        if (genre != null) properties.put("genre", genre);

        MutationResponse response = webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemCreateMutation(null, BOOK_TYPE, properties)), List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(MutationResponse.class)
                .returnResult().getResponseBody();
        return response.items().get(0).itemId();
    }

    private ProjectedItemsAndFacets project(CollectionProjectionSpec spec) {
        Predicate scopedToThisTest = new PropertyValuePredicate("testMarker", Operator.EQUALS, marker);
        Predicate combinedFilter = spec.filter() == null
                ? scopedToThisTest
                : new AndPredicate(List.of(scopedToThisTest, spec.filter()));
        var scopedSpec = new CollectionProjectionSpec(spec.itemTypeName(), spec.traitName(), spec.sortField(), spec.sortDirection(),
                combinedFilter, spec.facets(), spec.facetFilters(), spec.stateMachineFacets(), spec.offset(), spec.limit());
        var result = registerPartitionManager.project(fixture.bookTypeId(), scopedSpec, "http://binary");
        return new ProjectedItemsAndFacets(result);
    }

    // Thin wrapper purely so assertions below can say `.titles()` instead of repeating the same
    // "extract title property from every returned item" stream everywhere.
    private record ProjectedItemsAndFacets(ProjectionResult result) {
        List<String> titles() {
            return result.items().stream().map(i -> (String) i.properties().get("title")).toList();
        }
    }

    // --- Predicate translation ---

    @Test
    void propertyValuePredicate_equals_matchesOnlyTheExactValue() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new PropertyValuePredicate("title", Operator.EQUALS, "Dune"));

        assertThat(project(spec).titles()).containsExactly("Dune");
    }

    @Test
    void propertyValuePredicate_notEquals_excludesTheGivenValue() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, "title", "ASC",
                new PropertyValuePredicate("title", Operator.NOT_EQUALS, "Dune"));

        assertThat(project(spec).titles()).containsExactly("Foundation");
    }

    @Test
    void propertyValuePredicate_like_isCaseInsensitiveSubstringMatch() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new PropertyValuePredicate("title", Operator.LIKE, "%dun%"));

        assertThat(project(spec).titles()).containsExactly("Dune");
    }

    @Test
    void propertyValuePredicate_comparisonOperators_onNumericProperty() {
        // pageCount is stored as JSONB text (rt.properties->>'id'), and PropertyValuePredicate.value
        // is itself a plain String -- comparisons are therefore lexicographic, not numeric. Using
        // same-digit-length values (300/400/500) keeps lexicographic and numeric ordering in
        // agreement, so this test exercises the operator translation itself without also asserting
        // on that text-vs-numeric-comparison behavior, which is a separate concern.
        createBook("Short", 300, true, "Fiction");
        createBook("Medium", 400, true, "Fiction");
        createBook("Long", 500, true, "Fiction");

        assertThat(project(new CollectionProjectionSpec(BOOK_TYPE, "title", "ASC",
                new PropertyValuePredicate("pageCount", Operator.GREATER_THAN, "400"))).titles())
                .containsExactly("Long");

        assertThat(project(new CollectionProjectionSpec(BOOK_TYPE, "title", "ASC",
                new PropertyValuePredicate("pageCount", Operator.GREATER_THAN_OR_EQUAL, "400"))).titles())
                .containsExactly("Long", "Medium");

        assertThat(project(new CollectionProjectionSpec(BOOK_TYPE, "title", "ASC",
                new PropertyValuePredicate("pageCount", Operator.LESS_THAN, "400"))).titles())
                .containsExactly("Short");

        assertThat(project(new CollectionProjectionSpec(BOOK_TYPE, "title", "ASC",
                new PropertyValuePredicate("pageCount", Operator.LESS_THAN_OR_EQUAL, "400"))).titles())
                .containsExactly("Medium", "Short");
    }

    @Test
    void andPredicate_requiresAllChildrenToMatch() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Dune Messiah", 300, false, "Fiction");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new AndPredicate(List.of(
                        new PropertyValuePredicate("title", Operator.LIKE, "%dune%"),
                        new PropertyValuePredicate("inStock", Operator.EQUALS, "true"))));

        assertThat(project(spec).titles()).containsExactly("Dune");
    }

    @Test
    void orPredicate_matchesIfEitherChildMatches() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Reference");
        createBook("Neuromancer", 300, true, "Reference");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, "title", "ASC",
                new OrPredicate(List.of(
                        new PropertyValuePredicate("title", Operator.EQUALS, "Dune"),
                        new PropertyValuePredicate("title", Operator.EQUALS, "Foundation"))));

        assertThat(project(spec).titles()).containsExactly("Dune", "Foundation");
    }

    @Test
    void notPredicate_invertsTheChildMatch() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Reference");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new NotPredicate(new PropertyValuePredicate("title", Operator.EQUALS, "Dune")));

        assertThat(project(spec).titles()).containsExactly("Foundation");
    }

    @Test
    void propertyExistencePredicate_matchesOnlyItemsWhereThePropertyWasSet() {
        createBook("Dune", 400, true, "Fiction");
        createBook(null, 100, true, "Fiction"); // no title at all -- the point of this test

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new PropertyExistencePredicate("title"));

        assertThat(project(spec).titles()).containsExactly("Dune");
    }

    @Test
    void stateValuePredicate_matchesItemsCurrentlyInThatState() {
        UUID outOfStockBook = createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");
        registerPartitionManager.setItemState(outOfStockBook, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE,
                RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new StateValuePredicate(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE,
                        RegisterProjectionTestDomainInitializer.OUT_OF_STOCK));

        assertThat(project(spec).titles()).containsExactly("Dune");
    }

    @Test
    void predicateOnUnknownProperty_throwsIllegalArgumentException() {
        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new PropertyValuePredicate("noSuchProperty", Operator.EQUALS, "x"));

        assertThatThrownBy(() -> project(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noSuchProperty");
    }

    @Test
    void stateValuePredicateOnUnknownStateMachineOrState_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> project(new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new StateValuePredicate("NoSuchMachine", "Available"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> project(new CollectionProjectionSpec(BOOK_TYPE, null, null,
                new StateValuePredicate(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, "NoSuchState"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Faceting ---

    @Test
    void termsFacet_onBooleanProperty_countsEachDistinctValue() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");
        createBook("Neuromancer", 300, false, "Fiction");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                List.of("inStock"), null, null, null, null);

        Map<String, List<FacetBucket>> facets = project(spec).result().facets();
        assertThat(facets.get("inStock"))
                .extracting(FacetBucket::value, FacetBucket::count)
                .containsExactlyInAnyOrder(
                        tuple("true", 2L),
                        tuple("false", 1L));
    }

    @Test
    void termsFacet_onControlledListBackedStringProperty_countsEachDistinctValue() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Neuromancer", 300, true, "Fiction");
        createBook("The Pragmatic Programmer", 300, true, "Reference");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                List.of("genre"), null, null, null, null);

        Map<String, List<FacetBucket>> facets = project(spec).result().facets();
        assertThat(facets.get("genre"))
                .extracting(FacetBucket::value, FacetBucket::count)
                .containsExactlyInAnyOrder(
                        tuple("Fiction", 2L),
                        tuple("Reference", 1L));
    }

    @Test
    void requestedFacetsAsEmptyList_autoPopulatesFromEveryFacetableProperty() {
        createBook("Dune", 400, true, "Fiction");

        // Empty list (not null) means "every facetable property on this item type" --
        // facetableFieldsFor's own contract. title/pageCount aren't facetable (title has no
        // controlled list, pageCount isn't STRING/BOOLEAN), so only inStock and genre should
        // show up here.
        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                List.of(), null, null, null, null);

        assertThat(project(spec).result().facets().keySet()).containsExactlyInAnyOrder("inStock", "genre");
    }

    @Test
    void facetFilter_withTermsValues_narrowsFacetedCountButLeavesTotalCountAlone() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");
        createBook("The Pragmatic Programmer", 300, true, "Reference");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                null, List.of(new TermsFacetFilter("genre", List.of("Reference"), false)), null, null, null);

        var result = project(spec).result();
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.facetedCount()).isEqualTo(1);
        assertThat(project(spec).titles()).containsExactly("The Pragmatic Programmer");
    }

    @Test
    void facetFilter_includeNull_matchesItemsMissingThatProperty() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Untitled Draft", 100, true, null);

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                null, List.of(new TermsFacetFilter("genre", List.of(), true)), null, null, null);

        assertThat(project(spec).titles()).containsExactly("Untitled Draft");
    }

    @Test
    void facetIsDisjunctive_ownFilterDoesNotNarrowItsOwnBucketCounts() {
        // "Disjunctive" facets: a facet's own bucket counts are computed ignoring that facet's own
        // active filter (so a user can still see how many books there'd be in Fiction after
        // switching away from Reference) -- otherwise picking any filter value would instantly
        // collapse that same facet down to one bucket.
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");
        createBook("The Pragmatic Programmer", 300, true, "Reference");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                List.of("genre"), List.of(new TermsFacetFilter("genre", List.of("Reference"), false)), null, null, null);

        Map<String, List<FacetBucket>> facets = project(spec).result().facets();
        assertThat(facets.get("genre"))
                .extracting(FacetBucket::value, FacetBucket::count)
                .containsExactlyInAnyOrder(
                        tuple("Fiction", 2L),
                        tuple("Reference", 1L));
    }

    @Test
    void stateMachineFacet_countsItemsByCurrentState() {
        // Creating an item never assigns it a state automatically -- setItemState's own comment on
        // why (no "perform transition" mutation exists yet) -- so both items need an explicit call
        // to have a recorded state at all, even the one meant to represent "Available".
        UUID available = createBook("Dune", 400, true, "Fiction");
        UUID outOfStock = createBook("Foundation", 300, true, "Fiction");
        registerPartitionManager.setItemState(available, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE,
                RegisterProjectionTestDomainInitializer.AVAILABLE);
        registerPartitionManager.setItemState(outOfStock, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE,
                RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                null, null, List.of(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE), null, null);

        Map<String, List<FacetBucket>> stateFacets = project(spec).result().stateMachineFacets();
        assertThat(stateFacets.get(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE))
                .extracting(FacetBucket::label, FacetBucket::count)
                .containsExactlyInAnyOrder(
                        tuple(RegisterProjectionTestDomainInitializer.AVAILABLE, 1L),
                        tuple(RegisterProjectionTestDomainInitializer.OUT_OF_STOCK, 1L));
    }

    @Test
    void invalidFacetFieldName_throwsIllegalArgumentException() {
        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                List.of("not a valid field; drop table books"), null, null, null, null);

        assertThatThrownBy(() -> project(spec)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedFacetFilterTypes_throwUnsupportedOperationException() {
        var rangeSpec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                null, List.of(new RangeFacetFilter("pageCount", null, null)), null, null, null);
        assertThatThrownBy(() -> project(rangeSpec)).isInstanceOf(UnsupportedOperationException.class);

        var dateRangeSpec = new CollectionProjectionSpec(BOOK_TYPE, null, null, null, null,
                null, List.of(new DateRangeFacetFilter("pageCount", LocalDate.now(), LocalDate.now())), null, null, null);
        assertThatThrownBy(() -> project(dateRangeSpec)).isInstanceOf(UnsupportedOperationException.class);
    }

    // --- Sorting and pagination ---

    @Test
    void sorting_byPropertyColumn_ordersAscendingOrDescendingAsRequested() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");
        createBook("Neuromancer", 300, true, "Fiction");

        assertThat(project(new CollectionProjectionSpec(BOOK_TYPE, "title", "ASC")).titles())
                .containsExactly("Dune", "Foundation", "Neuromancer");
        assertThat(project(new CollectionProjectionSpec(BOOK_TYPE, "title", "DESC")).titles())
                .containsExactly("Neuromancer", "Foundation", "Dune");
    }

    @Test
    void sorting_bySystemColumn_isAcceptedAlongsidePropertyColumns() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");

        // "itemId" is one of SYSTEM_SORT_COLUMNS -- resolved straight to a real column (ri.item_id)
        // rather than going through resolvePropertyId, unlike a property-name sort field.
        var spec = new CollectionProjectionSpec(BOOK_TYPE, "itemId", "ASC");
        assertThat(project(spec).titles()).hasSize(2);
    }

    @Test
    void pagination_limitAndOffset_selectTheCorrectSlice() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");
        createBook("Neuromancer", 300, true, "Fiction");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, null, "title", "ASC", null, null, null, null, 1, 1);
        assertThat(project(spec).titles()).containsExactly("Foundation");
    }

    @Test
    void pagination_nullLimit_stillReturnsEveryMatchingItemUnderTheDefaultCap() {
        createBook("Dune", 400, true, "Fiction");
        createBook("Foundation", 300, true, "Fiction");

        var spec = new CollectionProjectionSpec(BOOK_TYPE, "title", "ASC");
        assertThat(project(spec).titles()).containsExactly("Dune", "Foundation");
    }

    // --- Link expansion ---

    @Test
    void projectedItem_includesLinkedItemsUnderTheirPerspectiveName() {
        UUID bookId = createBook("Dune", 400, true, "Fiction");

        MutationResponse authorResponse = webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(
                        List.of(new ItemCreateMutation(null, "RegisterProjectionTestAuthor", Map.of("name", "Frank Herbert"))),
                        List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(MutationResponse.class)
                .returnResult().getResponseBody();
        UUID authorId = authorResponse.items().get(0).itemId();

        webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(), List.of(new LinkCreateMutation(
                        new LinkEndpointReference("authors", new ExistingItemReference(bookId)),
                        new LinkEndpointReference("books", new ExistingItemReference(authorId)),
                        Map.of()))))
                .exchange()
                .expectStatus().isOk();

        var book = registerPartitionManager.projectOne(fixture.bookTypeId(), bookId, "http://binary").orElseThrow();
        assertThat(book.links().get("authors"))
                .extracting(link -> link.item().properties().get("name"))
                .containsExactly("Frank Herbert");
    }

    // --- setItemState ---

    @Test
    void setItemState_thenProjectOne_reflectsTheNewCurrentState() {
        UUID bookId = createBook("Dune", 400, true, "Fiction");

        registerPartitionManager.setItemState(bookId, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE,
                RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);

        var book = registerPartitionManager.projectOne(fixture.bookTypeId(), bookId, "http://binary").orElseThrow();
        assertThat(book.states().get(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE).currentState())
                .isEqualTo(RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);
    }

    @Test
    void setItemStateForUnknownItem_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> registerPartitionManager.setItemState(UUID.randomUUID(),
                RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, RegisterProjectionTestDomainInitializer.OUT_OF_STOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Schema-change table management ---

    @Test
    void deletingAnItemType_dropsItsRegisterTable() {
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "RegisterProjectionTestThrowaway", "created only to be deleted in this test", List.of(), null, false, null)));
        UUID throwawayTypeId = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals("RegisterProjectionTestThrowaway"))
                .findFirst().orElseThrow().id();
        String tableName = RegisterPartitionManager.tableNameFor(throwawayTypeId);

        assertThat(tableExists(tableName)).isTrue();

        schemaManager.applyMutations(List.of(new DeleteItemDefinitionMutation(throwawayTypeId)));

        assertThat(tableExists(tableName)).isFalse();
    }

    private boolean tableExists(String tableName) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = :tableName)")
                .param("tableName", tableName)
                .query(Boolean.class)
                .single();
    }

    // --- Malformed data defensive handling ---

    @Test
    void malformedPropertiesJsonInTheRegisterRow_failsClearlyRatherThanSilently() {
        UUID bookId = createBook("Dune", 400, true, "Fiction");
        String tableName = RegisterPartitionManager.tableNameFor(fixture.bookTypeId());

        // Postgres's jsonb column type itself rejects syntactically invalid JSON at write time, so
        // there's no way to get truly malformed JSON into this column -- what parseJsonb's catch
        // block actually guards against is syntactically valid JSON of the wrong shape. A JSON
        // array passes Postgres's own validation but can't deserialize into the Map parseJsonb
        // expects, which is exactly what reaches the catch block in practice (a properties column
        // that isn't a JSON object at all).
        jdbcClient.sql("UPDATE " + tableName + " SET properties = '[1, 2, 3]'::jsonb WHERE register_item_id = "
                        + "(SELECT id FROM register_item WHERE item_id = :itemId)")
                .param("itemId", bookId)
                .update();

        assertThatThrownBy(() -> registerPartitionManager.projectOne(fixture.bookTypeId(), bookId, "http://binary"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse properties JSONB");
    }
}
