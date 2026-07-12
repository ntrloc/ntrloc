package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Runs once during SchemaManager's constructor, before RegisterInitializer builds per-type
// tables -- the only way test item/link types can exist in time to get their register tables.
@Component
public class CoordinatorTestDomainInitializer implements DomainInitializer {

    private UUID productTypeId;
    private UUID contributorTypeId;
    private UUID linkTypeId;
    private UUID productPerspectiveId;
    private UUID contributorPerspectiveId;

    @Override
    public void initSchema(SchemaRepository repo, ControlledListManager controlledListManager) {
        var product = repo.createItem("CoordinatorTestProduct", "Coordinator integration test fixture");
        var contributor = repo.createItem("CoordinatorTestContributor", "Coordinator integration test fixture");
        productTypeId = product.id();
        contributorTypeId = contributor.id();

        linkTypeId = repo.createLink();
        productPerspectiveId = repo.createPerspective(product.entityId(), linkTypeId, "products", "desc", 0, null).id();
        contributorPerspectiveId = repo.createPerspective(contributor.entityId(), linkTypeId, "contributors", "desc", 0, null).id();
    }

    @Override
    public void initData(JdbcClient jdbcClient, BinaryPartitionManager binaryPartitionManager) {
        // no-op
    }

    public UUID productTypeId() {
        return productTypeId;
    }

    public UUID contributorTypeId() {
        return contributorTypeId;
    }

    public UUID linkTypeId() {
        return linkTypeId;
    }

    public UUID productPerspectiveId() {
        return productPerspectiveId;
    }

    public UUID contributorPerspectiveId() {
        return contributorPerspectiveId;
    }
}
