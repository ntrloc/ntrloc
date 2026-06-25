package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.definition.IdentifiedItemDefinition;
import org.ntrloc.graph.schema.definition.IdentifiedPropertyDefinition;
import org.ntrloc.graph.schema.definition.ItemDefinition;
import org.ntrloc.graph.schema.definition.ItemLinkPerspectiveDefinition;
import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyUsage;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.model.admin.AdminItemDefinitionModel;
import org.ntrloc.graph.schema.model.admin.PropertyTypeModel;
import org.ntrloc.graph.schema.model.admin.AdminItemLinkPerspectiveModel;
import org.ntrloc.graph.schema.model.admin.AdminPropertyDefinitionModel;
import org.ntrloc.graph.schema.model.admin.AdminSchemaModel;
import org.ntrloc.graph.schema.model.calculated.ItemDefinitionModel;
import org.ntrloc.graph.schema.model.calculated.ItemLinkPerspectiveModel;
import org.ntrloc.graph.schema.model.calculated.PropertyDefinitionModel;
import org.ntrloc.graph.schema.model.calculated.SchemaModel;
import org.ntrloc.graph.schema.repository.ItemDefinitionRepository;
import org.ntrloc.graph.schema.repository.ItemLinkPerspectiveDefinitionRepository;
import org.ntrloc.graph.schema.repository.ItemPropertyRepository;
import org.ntrloc.graph.schema.repository.LinkDefinitionRepository;
import org.ntrloc.graph.schema.repository.LinkPropertyRepository;
import org.ntrloc.graph.schema.repository.PropertyDefinitionRepository;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@DependsOn("jdbcSchemaInitializer")
public class SchemaManager {

    private final LinkPropertyRepository linkPropertyRepository;
    private final ItemLinkPerspectiveDefinitionRepository itemLinkPerspectiveDefinitionRepository;
    private ItemDefinitionRepository itemDefinitionRepository;
    private ItemPropertyRepository itemPropertyRepository;
    private LinkDefinitionRepository linkDefinitionRepository;
    private PropertyDefinitionRepository propertyDefinitionRepository;
    private ItemLinkPerspectiveDefinitionRepository linkPerspectiveDefinitionRepository;

    public SchemaManager(ItemDefinitionRepository itemDefinitionRepository,
                         LinkDefinitionRepository linkDefinitionRepository,
                         ItemPropertyRepository itemPropertyRepository,
                         PropertyDefinitionRepository propertyDefinitionRepository,
                         ItemLinkPerspectiveDefinitionRepository linkPerspectiveDefinitionRepository,
                         LinkPropertyRepository linkPropertyRepository,
                         ItemLinkPerspectiveDefinitionRepository itemLinkPerspectiveDefinitionRepository) {
        this.itemDefinitionRepository = itemDefinitionRepository;
        this.linkDefinitionRepository = linkDefinitionRepository;
        this.itemPropertyRepository = itemPropertyRepository;
        this.propertyDefinitionRepository = propertyDefinitionRepository;
        this.linkPerspectiveDefinitionRepository = linkPerspectiveDefinitionRepository;
        this.linkPropertyRepository = linkPropertyRepository;
        this.itemLinkPerspectiveDefinitionRepository = itemLinkPerspectiveDefinitionRepository;

        init();
    }

    private void init() {
        // Item types
        IdentifiedItemDefinition productDefinition       = itemDefinitionRepository.createItemDefinition(new ItemDefinition("Product", "A product (book, DVD, etc.) sold by the company"));
        IdentifiedItemDefinition coverDefinition         = itemDefinitionRepository.createItemDefinition(new ItemDefinition("Cover", "A cover for a product"));
        IdentifiedItemDefinition alternateCoverDefinition = itemDefinitionRepository.createItemDefinition(new ItemDefinition("AlternateCover", "An alternate cover for a product"));
        IdentifiedItemDefinition contributorDefinition   = itemDefinitionRepository.createItemDefinition(new ItemDefinition("Contributor", "A person who contributed to a product in some way (author, illustrator, editor, etc.)"));

        // Product properties
        var isbnProperty = propertyDefinitionRepository.create(new PropertyDefinition("ISBN 13", null, PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL));
        itemPropertyRepository.associate(productDefinition.id(), isbnProperty.id());

        // Link: Product ↔ Cover
        var productCoverLink = linkDefinitionRepository.createLinkDefinition();
        var createDateProperty = propertyDefinitionRepository.create(new PropertyDefinition("createDate", null, PropertyType.DATE, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL));
        linkPropertyRepository.associate(productCoverLink.id(), createDateProperty.id());
        linkPerspectiveDefinitionRepository.create(new ItemLinkPerspectiveDefinition(productDefinition.id(), productCoverLink.id(), "cover", null, 0, 1));
        linkPerspectiveDefinitionRepository.create(new ItemLinkPerspectiveDefinition(coverDefinition.id(), productCoverLink.id(), "product", null, 1, 1));

        // Link: Product ↔ AlternateCover
        var productAlternateCoverLink = linkDefinitionRepository.createLinkDefinition();
        linkPerspectiveDefinitionRepository.create(new ItemLinkPerspectiveDefinition(productDefinition.id(), productAlternateCoverLink.id(), "cover", null, 0, 1));
        linkPerspectiveDefinitionRepository.create(new ItemLinkPerspectiveDefinition(alternateCoverDefinition.id(), productAlternateCoverLink.id(), "product", null, 1, 1));

        // Link: Product ↔ Contributor
        var productContributorLink = linkDefinitionRepository.createLinkDefinition();
        linkPerspectiveDefinitionRepository.create(new ItemLinkPerspectiveDefinition(productDefinition.id(), productContributorLink.id(), "contributors", null, 0, null));
        linkPerspectiveDefinitionRepository.create(new ItemLinkPerspectiveDefinition(contributorDefinition.id(), productContributorLink.id(), "product", null, 0, null));
    }

    public SchemaModel getSchema() {
        var itemDefinitions = itemDefinitionRepository.getItemDefinitions();
        var itemDefinitionMap = itemDefinitions.stream().collect(Collectors.toMap(IdentifiedItemDefinition::id, itemDef -> itemDef));
        var itemPropertiesMap = itemPropertyRepository.mapAllByItemType();
        var linkPropertiesMap = linkPropertyRepository.mapAllByLinkType();
        var itemLinkPerspectives = itemLinkPerspectiveDefinitionRepository.mapAllByItemType();

        var itemDefinitionDtos = itemDefinitions.stream().map(itemDefinition -> {
            var propertyDefinitions = itemPropertiesMap.get(itemDefinition.id());
            var propertyModels = propertyDefinitions == null
                    ? null
                    : propertyDefinitions.stream()
                            .map(this::mapToCalculatedModel)
                            .toList();

            var linkPerspectives = itemLinkPerspectives.get(itemDefinition.id());
            var links = linkPerspectives == null
                    ? null
                    : linkPerspectives.stream()
                            .map(perspective -> {
                                var inverseLink = itemLinkPerspectiveDefinitionRepository.findInversePerspective(perspective.linkId(), perspective.id());
                                var inverseItem = itemDefinitionMap.get(inverseLink.definition().itemDefinitionId());
                                var linkProperties = linkPropertiesMap.get(perspective.linkId());
                                var linkPropertyModels = linkProperties == null
                                        ? null
                                        : linkProperties.stream().map(this::mapToCalculatedModel).toList();
                                return Map.entry(perspective.name(), new ItemLinkPerspectiveModel(inverseItem.name(), perspective.description(), perspective.minCardinality(), perspective.maxCardinality(), linkPropertyModels));
                            })
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey,
                                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                            ));

            return new ItemDefinitionModel(itemDefinition.id(), itemDefinition.name(), itemDefinition.description(), propertyModels, links);
        }).toList();

        return new SchemaModel(itemDefinitionDtos);
    }

    public AdminSchemaModel getAdminSchema() {
        var itemDefinitions = itemDefinitionRepository.getItemDefinitions();
        var itemDefinitionMap = itemDefinitions.stream().collect(Collectors.toMap(IdentifiedItemDefinition::id, itemDef -> itemDef));
        var itemPropertiesMap = itemPropertyRepository.mapAllByItemType();
        var linkPropertiesMap = linkPropertyRepository.mapAllByLinkType();
        var itemLinkPerspectives = itemLinkPerspectiveDefinitionRepository.mapAllByItemType();

        var itemDefinitionDtos = itemDefinitions.stream().map(itemDefinition -> {
            var propertyDefinitions = itemPropertiesMap.get(itemDefinition.id());
            var propertyModels = propertyDefinitions == null
                    ? null
                    : propertyDefinitions.stream()
                            .map(this::mapToAdminModel)
                            .toList();

            var linkPerspectives = itemLinkPerspectives.get(itemDefinition.id());
            var links = linkPerspectives == null
                    ? null
                    : linkPerspectives.stream()
                            .map(perspective -> {
                                var inverseLink = itemLinkPerspectiveDefinitionRepository.findInversePerspective(perspective.linkId(), perspective.id());
                                var inverseItem = itemDefinitionMap.get(inverseLink.definition().itemDefinitionId());
                                var linkProperties = linkPropertiesMap.get(perspective.linkId());
                                var linkPropertyModels = linkProperties == null
                                        ? null
                                        : linkProperties.stream().map(this::mapToAdminModel).toList();
                                return Map.entry(perspective.name(), new AdminItemLinkPerspectiveModel(inverseItem.name(), perspective.description(), perspective.minCardinality(), perspective.maxCardinality(), linkPropertyModels));
                            })
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey,
                                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                            ));

            return new AdminItemDefinitionModel(itemDefinition.id(), itemDefinition.name(), itemDefinition.description(), propertyModels, links);
        }).toList();

        List<PropertyTypeModel> propertyTypes = Arrays.stream(PropertyType.values())
                .map(type -> new PropertyTypeModel(type, type.validCardinalities()))
                .toList();

        return new AdminSchemaModel(itemDefinitionDtos, propertyTypes);
    }

    private PropertyDefinitionModel mapToCalculatedModel(IdentifiedPropertyDefinition propertyDef) {
        return new PropertyDefinitionModel(propertyDef.id(), propertyDef.name(), propertyDef.description(), propertyDef.type(), propertyDef.cardinality());
    }

    private AdminPropertyDefinitionModel mapToAdminModel(IdentifiedPropertyDefinition propertyDef) {
        return new AdminPropertyDefinitionModel(propertyDef.id(), propertyDef.name(), propertyDef.description(), propertyDef.type(), propertyDef.cardinality(), propertyDef.usage());
    }

}
