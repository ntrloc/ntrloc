package org.ntrloc.graph;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.ntrloc.graph.db.EntityReadService;
import org.ntrloc.graph.db.EntityWriteService;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinatorImpl;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.draft.DraftAccessService;
import org.ntrloc.graph.draft.DraftGrantStore;
import org.ntrloc.graph.draft.DraftService;
import org.ntrloc.graph.draft.DraftStore;
import org.ntrloc.graph.db.partition.authorization.ManualMarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.repository.GrantsView;
import org.ntrloc.graph.db.partition.authorization.repository.UserGrantsView;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStore;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreWriter;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStore;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStoreWriter;
import org.ntrloc.graph.db.partition.ledger.LedgerStore;
import org.ntrloc.graph.db.partition.ledger.LedgerStoreWriter;
import org.ntrloc.graph.db.partition.register.RegisterStore;
import org.ntrloc.graph.db.partition.register.RegisterStoreWriter;
import org.ntrloc.graph.mutation.MutationRequestProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
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

    // §7: Access answers permission questions about a principal; it doesn't depend on how that principal
    // was authenticated or resolved. (The access admin controllers and startup initializers, which look
    // up users and groups, are I/O and may.)
    @ArchTest
    static final ArchRule accessDoesNotDependOnSecurity = noClasses()
            .that().belongToAnyOf(AccessService.class, ManualMarkerAssignmentService.class, UserGrantsView.class, GrantsView.class,
                    GrantStore.class, GrantStoreReader.class, GrantStoreWriter.class,
                    MarkerStore.class, MarkerStoreReader.class, MarkerStoreWriter.class)
            .should().dependOnClassesThat().resideInAPackage("org.ntrloc.graph.db.partition.security..")
            .because("the access service and store work from the principal alone");

    // Rule 7: outside its own package a store is reached through its reader or writer interface, so who
    // holds the writer is visible (and checked above).
    @ArchTest
    static final ArchRule storesAreUsedThroughTheirInterfaces = noClasses()
            .that().resideOutsideOfPackages("org.ntrloc.graph.db.partition.register..", "org.ntrloc.graph.db.partition.ledger..",
                    "org.ntrloc.graph.db.partition.authorization.repository..")
            .should().dependOnClassesThat().belongToAnyOf(RegisterStore.class, LedgerStore.class, GrantStore.class, MarkerStore.class)
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
            .that().belongToAnyOf(RegisterStore.class, LedgerStore.class, GrantStore.class, MarkerStore.class)
            .should().dependOnClassesThat(serviceLayer())
            .because("stores never depend on services");

    // ---- Drafts (docs/ntrloc-system-model-notes.md §8.6, §9.3; docs/ntrloc-drafts-plan.md step 0.2) ----

    // §9.3: the live path has no draft code in it. Draft classes are named Draft*; only other draft
    // classes, and the draft machinery's own package (registry, routing, apply), may refer to them.
    @ArchTest
    static final ArchRule theLivePathNeverRefersToDraftClasses = noClasses()
            .that().haveSimpleNameNotStartingWith("Draft")
            .and().resideOutsideOfPackage("org.ntrloc.graph.draft..")
            .should().dependOnClassesThat().haveSimpleNameStartingWith("Draft")
            .because("draft code is kept off the live path: live and draft services differ only in their stores");

    // A store and the one service allowed to write it. `writeAccess` is what writing takes: the store's
    // writer interface, or the store itself if it has none. Listed as each partition is restructured.
    private record SingleWriter(Class<?> writeAccess, Class<?> writer) {}

    private static final List<SingleWriter> SINGLE_WRITERS = List.of(
            new SingleWriter(GrantStoreWriter.class, AccessService.class),
            new SingleWriter(MarkerStoreWriter.class, AccessService.class),
            new SingleWriter(DraftStore.class, DraftService.class),
            new SingleWriter(DraftGrantStore.class, DraftAccessService.class));

    // §8.6: each store has exactly one service that writes it (a service may own several stores).
    @ArchTest
    static void eachStoreHasOneWritingService(JavaClasses classes) {
        for (SingleWriter entry : SINGLE_WRITERS) {
            noClasses()
                    .that().doNotBelongToAnyOf(entry.writer(), entry.writeAccess())
                    .and().areNotAssignableTo(entry.writeAccess())
                    .should().dependOnClassesThat().belongToAnyOf(entry.writeAccess())
                    .because(entry.writer().getSimpleName() + " is the only service that writes through "
                            + entry.writeAccess().getSimpleName())
                    .check(classes);
        }
    }

    // Stores covered by the transaction rule below. Listed as each partition is restructured.
    private static final List<Class<?>> TRANSACTION_FREE_STORES = List.of(GrantStore.class, MarkerStore.class, DraftStore.class, DraftGrantStore.class);

    // §8.6: stores own their storage, not transactions. The logical layer decides where a transaction
    // starts and ends; a store method needing atomicity requires one (Propagation.MANDATORY) without
    // starting it.
    @ArchTest
    static void storesNeverStartTransactions(JavaClasses classes) {
        if (TRANSACTION_FREE_STORES.isEmpty()) {
            return;
        }
        Class<?>[] stores = TRANSACTION_FREE_STORES.toArray(Class<?>[]::new);
        noClasses()
                .that().belongToAnyOf(stores)
                .should().dependOnClassesThat().belongToAnyOf(TransactionTemplate.class, PlatformTransactionManager.class)
                .because("stores never start transactions")
                .check(classes);
        codeUnits()
                .that().areDeclaredInClassesThat().belongToAnyOf(stores)
                .should(onlyRequireATransaction())
                .because("a store method may require a transaction but never starts one")
                .check(classes);
    }

    private static ArchCondition<JavaCodeUnit> onlyRequireATransaction() {
        return new ArchCondition<>("be @Transactional only with Propagation.MANDATORY") {
            @Override
            public void check(JavaCodeUnit unit, ConditionEvents events) {
                checkAnnotation(unit.tryGetAnnotationOfType(Transactional.class.getName()), unit.getFullName(), events);
                checkAnnotation(unit.getOwner().tryGetAnnotationOfType(Transactional.class.getName()), unit.getOwner().getName(), events);
            }

            private void checkAnnotation(java.util.Optional<? extends JavaAnnotation<?>> annotation, String where, ConditionEvents events) {
                annotation.ifPresent(a -> {
                    Object propagation = a.get("propagation").orElse(null);
                    boolean mandatory = propagation != null && propagation.toString().endsWith(Propagation.MANDATORY.name());
                    if (!mandatory) {
                        events.add(SimpleConditionEvent.violated(a, where + " starts a transaction (@Transactional without Propagation.MANDATORY)"));
                    }
                });
            }
        };
    }

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
