package org.ntrloc.graph;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.ntrloc.graph.db.EntityReadService;
import org.ntrloc.graph.db.EntityWriteService;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinatorImpl;
import org.ntrloc.graph.db.partition.ledger.LedgerStore;
import org.ntrloc.graph.db.partition.ledger.LedgerStoreWriter;
import org.ntrloc.graph.db.partition.register.RegisterStore;
import org.ntrloc.graph.db.partition.register.RegisterStoreWriter;

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

    // Rule 7: outside its own package a store is reached through its reader or writer interface, so who
    // holds the writer is visible (and checked above).
    @ArchTest
    static final ArchRule storesAreUsedThroughTheirInterfaces = noClasses()
            .that().resideOutsideOfPackages("org.ntrloc.graph.db.partition.register..", "org.ntrloc.graph.db.partition.ledger..")
            .should().dependOnClassesThat().belongToAnyOf(RegisterStore.class, LedgerStore.class)
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
            .that().belongToAnyOf(RegisterStore.class, LedgerStore.class)
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
                .and(not(resideInAPackage("org.ntrloc.graph.db.partition.schema.event..")))
                .as("service-layer classes (schema change events aside)");
    }
}
