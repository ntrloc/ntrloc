package org.ntrloc.graph.db.mutation;

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
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Dedicated fixture for MutationRequestProcessorIntegrationTest -- kept separate from
// CoordinatorTestDomainInitializer since it needs two things that fixture doesn't have:
//
//  - A property of every scalar type MutationRequestProcessor's validateScalar() switches on
//    (CoordinatorTestDomainInitializer only has STRING/DATE), to reach the BINARY-rejection and
//    LONG/DATETIME branches.
//  - Two link definitions sharing the *same* perspective-name pair between A and B (link1, link2)
//    -- resolveLinkTypeId's "ambiguous" branch (multiple link types connect the same perspective
//    pair) needs a genuine second candidate, which no single-link fixture can ever produce -- plus
//    a third, disjoint item type C with its own self-contained link (link3, sharing no perspective
//    name with A/B at all) to reach the "no common link" branch the opposite way.
@Component
public class MutationRequestProcessorTestDomainInitializer implements DomainInitializer, ApplicationRunner {

    private UUID aTypeId;
    private UUID bTypeId;
    private UUID cTypeId;
    private UUID dTypeId;

    private final SchemaManager schemaManager;
    private final ControlledListManager controlledListManager;

    public MutationRequestProcessorTestDomainInitializer(SchemaManager schemaManager, ControlledListManager controlledListManager) {
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
                "MutReqProcA", "MutationRequestProcessor test fixture",
                List.of(
                        property("attachment", PropertyType.BINARY, PropertyCardinality.SINGLE),
                        property("count", PropertyType.LONG, PropertyCardinality.SINGLE),
                        property("createdAt", PropertyType.DATETIME, PropertyCardinality.SINGLE),
                        property("extra", PropertyType.OBJECT, PropertyCardinality.SINGLE)))));
        aTypeId = findItem("MutReqProcA").id();

        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "MutReqProcB", "MutationRequestProcessor test fixture", List.of())));
        bTypeId = findItem("MutReqProcB").id();

        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "MutReqProcC", "MutationRequestProcessor test fixture", List.of())));
        cTypeId = findItem("MutReqProcC").id();

        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "MutReqProcD", "MutationRequestProcessor test fixture", List.of())));
        dTypeId = findItem("MutReqProcD").id();

        // link1 and link2: two distinct link types both using the perspective-name pair
        // ("toB" on A, "fromA" on B) -- A.toB and B.fromA each end up with TWO candidate link
        // types, so connecting them is genuinely ambiguous (resolveLinkTypeId's own comment).
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(aTypeId, "toB", "d", 0, null),
                new CreatePerspectiveDefinitionMutation(bTypeId, "fromA", "d", 0, null)))));
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(aTypeId, "toB", "d", 0, null),
                new CreatePerspectiveDefinitionMutation(bTypeId, "fromA", "d", 0, null)))));

        // link3: connects C and D, sharing no perspective name with A/B's link1/link2 at all --
        // connecting A.toB to C.onlyC has zero candidate link types in common (the opposite
        // failure mode from link1/link2's ambiguity). Deliberately two distinct item types rather
        // than a self-link on C, purely to keep this fixture's naming simple -- self-links are
        // supported (see SelfLinkIntegrationTest).
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(cTypeId, "onlyC", "d", 0, null),
                new CreatePerspectiveDefinitionMutation(dTypeId, "onlyD", "d", 0, null)))));
    }

    private CreatePropertyDefinitionMutation property(String name, PropertyType type, PropertyCardinality cardinality) {
        return new CreatePropertyDefinitionMutation(name, "MutationRequestProcessor test fixture", type, cardinality, PropertyUsage.OPTIONAL);
    }

    private AdminItemDefinitionView findItem(String name) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    public UUID aTypeId() {
        return aTypeId;
    }

    public UUID bTypeId() {
        return bTypeId;
    }

    public UUID cTypeId() {
        return cTypeId;
    }

    public UUID dTypeId() {
        return dTypeId;
    }
}
