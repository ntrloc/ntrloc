package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
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
    private UUID namePropertyId;
    private UUID colorPropertyId;
    private UUID rolePropertyId;

    @Override
    public void initSchema(SchemaRepository repo, ControlledListManager controlledListManager) {
        var product = repo.createItem("CoordinatorTestProduct", "Coordinator integration test fixture");
        var contributor = repo.createItem("CoordinatorTestContributor", "Coordinator integration test fixture");
        productTypeId = product.id();
        contributorTypeId = contributor.id();

        namePropertyId = repo.createProperty("name", "Coordinator integration test fixture",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL).id();
        repo.associateItemProperty(productTypeId, namePropertyId);
        repo.associateItemProperty(contributorTypeId, namePropertyId);

        colorPropertyId = repo.createProperty("color", "Coordinator integration test fixture",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL).id();
        repo.associateItemProperty(productTypeId, colorPropertyId);

        UUID tagsPropertyId = repo.createProperty("tags", "Coordinator integration test fixture",
                PropertyType.STRING, PropertyCardinality.SET, PropertyUsage.OPTIONAL).id();
        repo.associateItemProperty(productTypeId, tagsPropertyId);

        UUID releaseDatePropertyId = repo.createProperty("releaseDate", "Coordinator integration test fixture",
                PropertyType.DATE, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL).id();
        repo.associateItemProperty(productTypeId, releaseDatePropertyId);

        linkTypeId = repo.createLink();
        productPerspectiveId = repo.createPerspective(product.entityId(), linkTypeId, "products", "desc", 0, null).id();
        contributorPerspectiveId = repo.createPerspective(contributor.entityId(), linkTypeId, "contributors", "desc", 0, null).id();

        rolePropertyId = repo.createProperty("role", "Coordinator integration test fixture",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL).id();
        repo.associateLinkProperty(linkTypeId, rolePropertyId);
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

    public UUID namePropertyId() {
        return namePropertyId;
    }

    public UUID colorPropertyId() {
        return colorPropertyId;
    }

    public UUID rolePropertyId() {
        return rolePropertyId;
    }
}
