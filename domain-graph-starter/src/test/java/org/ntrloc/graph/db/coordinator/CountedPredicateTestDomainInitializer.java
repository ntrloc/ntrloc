package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository;
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
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Dedicated fixture for CountedPredicateMarkerDecisionIntegrationTest -- mirrors the real,
// production Photographer marker-decision pipeline's own shape (self -> photos (each) -> portfolios
// (each) -> state machine) at a scale small enough to drive deliberately from a test: Photographer
// -> (photos) -> Photo -> (portfolios) -> Portfolio, with Portfolio carrying the one state machine
// the CountedPredicate reads. Kept separate from CoordinatorTestDomainInitializer (no link/state
// machine chain of its own) rather than extending it, same reasoning every other dedicated fixture in
// this test tree already uses.
@Component
@ConditionalOnProperty(prefix = "graph.security", name = "seed-test-data", havingValue = "true")
public class CountedPredicateTestDomainInitializer implements DomainInitializer, ApplicationRunner {

    public static final String PUBLICATION_MACHINE = "Publication";
    public static final String DRAFT = "Draft";
    public static final String PUBLISHED = "Published";

    private final SchemaManager schemaManager;
    private final MarkerDecisionPipelineRepository pipelineRepository;

    private UUID photographerTypeId;
    private UUID photoTypeId;
    private UUID portfolioTypeId;
    private UUID activePropertyId;
    private UUID photosPerspectiveId;
    private UUID portfoliosPerspectiveId;
    private UUID publicationMachineId;
    private UUID draftStateId;
    private UUID publishedStateId;

    public CountedPredicateTestDomainInitializer(SchemaManager schemaManager, MarkerDecisionPipelineRepository pipelineRepository) {
        this.schemaManager = schemaManager;
        this.pipelineRepository = pipelineRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        initSchema(schemaManager, null);
    }

    @Override
    public void initSchema(SchemaManager schemaManager, ControlledListManager controlledListManager) {
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "CountedPredicateTestPhotographer", "CountedPredicate integration test fixture",
                // The one property this fixture's pipeline actually watches -- see the test class's
                // own comment on why the CountedPredicate's own subject (photosPerspectiveId etc.)
                // can never itself be what triggers re-evaluation of an update to the Photographer.
                List.of(property("active", PropertyType.BOOLEAN, PropertyCardinality.SINGLE)),
                null, false, null)));
        photographerTypeId = findItem("CountedPredicateTestPhotographer").id();
        activePropertyId = findProperty(findItem("CountedPredicateTestPhotographer").properties(), "active");

        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "CountedPredicateTestPhoto", "CountedPredicate integration test fixture", List.of(), null, false, null)));
        photoTypeId = findItem("CountedPredicateTestPhoto").id();

        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "CountedPredicateTestPortfolio", "CountedPredicate integration test fixture", List.of(), null, false, null)));
        portfolioTypeId = findItem("CountedPredicateTestPortfolio").id();

        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(
                List.of(),
                List.of(
                        new CreatePerspectiveDefinitionMutation(photographerTypeId, "photos", "desc", 0, null),
                        new CreatePerspectiveDefinitionMutation(photoTypeId, "photographer", "desc", 0, null)))));
        photosPerspectiveId = findItem("CountedPredicateTestPhotographer").links().get("photos").get(0).id();

        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(
                List.of(),
                List.of(
                        new CreatePerspectiveDefinitionMutation(photoTypeId, "portfolios", "desc", 0, null),
                        new CreatePerspectiveDefinitionMutation(portfolioTypeId, "photo", "desc", 0, null)))));
        portfoliosPerspectiveId = findItem("CountedPredicateTestPhoto").links().get("portfolios").get(0).id();

        initStateMachine(schemaManager, portfolioTypeId, PUBLICATION_MACHINE,
                List.of(new StateDefinition(DRAFT), new StateDefinition(PUBLISHED)),
                List.of(
                        new TransitionDefinition(START_STATE, DRAFT, "start"),
                        new TransitionDefinition(DRAFT, PUBLISHED, "publish")));

        AdminStateMachineView machine = findItem("CountedPredicateTestPortfolio").stateMachines().stream()
                .filter(m -> m.name().equals(PUBLICATION_MACHINE))
                .findFirst()
                .orElseThrow();
        publicationMachineId = machine.id();
        draftStateId = machine.states().stream().filter(s -> s.name().equals(DRAFT)).findFirst().orElseThrow().id();
        publishedStateId = machine.states().stream().filter(s -> s.name().equals(PUBLISHED)).findFirst().orElseThrow().id();
    }

    private CreatePropertyDefinitionMutation property(String name, PropertyType type, PropertyCardinality cardinality) {
        return new CreatePropertyDefinitionMutation(name, "CountedPredicate integration test fixture", type, cardinality, PropertyUsage.OPTIONAL, false);
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

    // DefaultPipelineInitializer auto-creates a no-op pipeline for every item type the moment it
    // exists, including photographerTypeId -- same situation CoordinatorTestDomainInitializer's own
    // removeAutoCreatedPipelines documents. Call this from a test's own @BeforeEach before it creates
    // its own pipeline against photographerTypeId.
    public void removeAutoCreatedPipeline() {
        pipelineRepository.findByItemTypeId(photographerTypeId).ifPresent(p -> pipelineRepository.deletePipeline(p.id()));
    }

    public UUID photographerTypeId() {
        return photographerTypeId;
    }

    public UUID photoTypeId() {
        return photoTypeId;
    }

    public UUID portfolioTypeId() {
        return portfolioTypeId;
    }

    public UUID activePropertyId() {
        return activePropertyId;
    }

    public UUID photosPerspectiveId() {
        return photosPerspectiveId;
    }

    public UUID portfoliosPerspectiveId() {
        return portfoliosPerspectiveId;
    }

    public UUID publicationMachineId() {
        return publicationMachineId;
    }

    public UUID draftStateId() {
        return draftStateId;
    }

    public UUID publishedStateId() {
        return publishedStateId;
    }
}
