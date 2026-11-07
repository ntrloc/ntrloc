package org.ntrloc.graph;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.ntrloc.graph.db.EntityReadService;
import org.ntrloc.graph.db.EntityWriteService;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinatorImpl;
import org.ntrloc.graph.db.partition.authorization.ManualMarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.authorization.RequestPermissionContext;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStore;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStoreWriter;
import org.ntrloc.graph.db.partition.ledger.LedgerStore;
import org.ntrloc.graph.db.partition.ledger.LedgerStoreWriter;
import org.ntrloc.graph.db.partition.register.RegisterStore;
import org.ntrloc.graph.db.partition.register.RegisterStoreWriter;
import org.ntrloc.graph.mutation.MutationRequestProcessor;
import org.ntrloc.graph.principal.NtrlocPrincipal;

import java.util.Set;
import java.util.UUID;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

// The architecture's rules (docs/ntrloc-architecture-rationalization.md §2), checked against production
// classes. Tests may use the store classes directly to set up fixtures.
@AnalyzeClasses(packages = "org.ntrloc.graph", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    // Rule 2/7: the coordinator keeps the ledger and register consistent, so it alone writes them.
    @ArchTest
    static final ArchRule onlyTheCoordinatorWritesTheLedgerAndRegister = noClasses()
            .that().doNotBelongToAnyOf(LedgerRegisterCoordinatorImpl.class, RegisterStore.class, LedgerStore.class,
                    RegisterStoreWriter.class, LedgerStoreWriter.class)
            .should().dependOnClassesThat().belongToAnyOf(RegisterStoreWriter.class, LedgerStoreWriter.class)
            .because("only the ledger/register coordinator may write the ledger or the register");

    // §5.2: one write path. Item and link changes, transitions included, go through MutationRequestProcessor.
    // ManualMarkerAssignmentService is a known second path (§8), allowed here so the exception stays visible.
    @ArchTest
    static final ArchRule onlyTheMutationProcessorCallsTheCoordinator = noClasses()
            .that().resideOutsideOfPackage("org.ntrloc.graph.db.coordinator..")
            .and().doNotBelongToAnyOf(MutationRequestProcessor.class, ManualMarkerAssignmentService.class)
            .should().dependOnClassesThat().belongToAnyOf(LedgerRegisterCoordinator.class)
            .because("every write goes through MutationRequestProcessor");

    // A principal is only ever resolved, never fabricated (docs/ntrloc-principal-enforcement-plan.md, Part A).
    @ArchTest
    static final ArchRule onlyThePrincipalResolverCreatesPrincipals = noClasses()
            .that().doNotHaveFullyQualifiedName("org.ntrloc.graph.db.partition.security.PrincipalResolverImpl")
            .should().callConstructor(NtrlocPrincipal.class, UUID.class, String.class, String.class, Set.class, boolean.class)
            .because("an NtrlocPrincipal comes only from resolving a real user");

    // §7: Access answers permission questions about a principal; it doesn't depend on how that principal
    // was authenticated or resolved. (The access admin controllers and startup initializers, which look
    // up users and groups, are I/O and may.)
    @ArchTest
    static final ArchRule accessDoesNotDependOnSecurity = noClasses()
            .that().belongToAnyOf(PermissionService.class, ManualMarkerAssignmentService.class, RequestPermissionContext.class,
                    AccessStore.class, AccessStoreReader.class, AccessStoreWriter.class)
            .should().dependOnClassesThat().resideInAPackage("org.ntrloc.graph.db.partition.security..")
            .because("the access service and store work from the principal alone");

    // Rule 7: outside its own package a store is reached through its reader or writer interface, so who
    // holds the writer is visible (and checked above).
    @ArchTest
    static final ArchRule storesAreUsedThroughTheirInterfaces = noClasses()
            .that().resideOutsideOfPackages("org.ntrloc.graph.db.partition.register..", "org.ntrloc.graph.db.partition.ledger..",
                    "org.ntrloc.graph.db.partition.authorization.repository..")
            .should().dependOnClassesThat().belongToAnyOf(RegisterStore.class, LedgerStore.class, AccessStore.class)
            .because("stores are used through their <Name>StoreReader / <Name>StoreWriter interfaces");

    // Rule 5: the read side can be called by anything, so it never depends on the write side.
    @ArchTest
    static final ArchRule readsNeverDependOnWrites = noClasses()
            .that().belongToAnyOf(EntityReadService.class).or().resideInAPackage("org.ntrloc.graph.projection..")
            .should().dependOnClassesThat().belongToAnyOf(EntityWriteService.class, RegisterStoreWriter.class, LedgerStoreWriter.class)
            .orShould().dependOnClassesThat().resideInAnyPackage("org.ntrloc.graph.mutation..", "org.ntrloc.graph.db.coordinator..")
            .because("reads never depend on writes");

    // Rules 1-2: stores are CRUD on their own data and never call up into services. Schema change events are
    // the one way in (a store keeps its tables in step with the schema), which is a notification, not a call.
    @ArchTest
    static final ArchRule storesDoNotDependOnServices = noClasses()
            .that().belongToAnyOf(RegisterStore.class, LedgerStore.class, AccessStore.class)
            .should().dependOnClassesThat(serviceLayer())
            .because("stores never depend on services");

    private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> serviceLayer() {
        return resideInAnyPackage(
                "org.ntrloc.graph.db.partition.schema..",
                "org.ntrloc.graph.db.partition.authorization..",
                "org.ntrloc.graph.db.partition.binary..",
                "org.ntrloc.graph.db.coordinator..",
                "org.ntrloc.graph.mutation..",
                "org.ntrloc.graph.projection..")
                .and(not(resideInAnyPackage("org.ntrloc.graph.db.partition.schema.event..",
                        "org.ntrloc.graph.db.partition.authorization.repository..")))
                .as("service-layer classes (schema change events and the access store's own package aside)");
    }
}
