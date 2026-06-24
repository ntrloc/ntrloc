package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.definition.IdentifiedItemDefinition;
import org.ntrloc.graph.schema.definition.IdentifiedPropertyDefinition;
import org.ntrloc.graph.schema.definition.ItemDefinition;
import org.ntrloc.graph.schema.definition.ItemLinkPerspectiveDefinition;
import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.model.ItemDefinitionModel;
import org.ntrloc.graph.schema.model.ItemLinkPerspectiveModel;
import org.ntrloc.graph.schema.model.PropertyDefinitionModel;
import org.ntrloc.graph.schema.model.SchemaModel;
import org.ntrloc.graph.schema.repository.ItemDefinitionRepository;
import org.ntrloc.graph.schema.repository.ItemLinkPerspectiveDefinitionRepository;
import org.ntrloc.graph.schema.repository.ItemPropertyRepository;
import org.ntrloc.graph.schema.repository.LinkDefinitionRepository;
import org.ntrloc.graph.schema.repository.LinkPropertyRepository;
import org.ntrloc.graph.schema.repository.PropertyDefinitionRepository;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

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
        if (itemDefinitionRepository.getItemDefinitions().isEmpty()) {
            IdentifiedItemDefinition productDefinition = itemDefinitionRepository.createItemDefinition(new ItemDefinition("Product"));
            IdentifiedItemDefinition coverDefinition = itemDefinitionRepository.createItemDefinition(new ItemDefinition("Cover"));

            IdentifiedPropertyDefinition isbnProperty = propertyDefinitionRepository.create(new PropertyDefinition("ISBN 13", PropertyType.STRING, PropertyCardinality.SINGLE));
            itemPropertyRepository.associate(productDefinition.id(), isbnProperty.id());

            var productCoverLinkDefinition = linkDefinitionRepository.createLinkDefinition();

            var createDatePropertyDefinition = propertyDefinitionRepository.create( new PropertyDefinition("createDate", PropertyType.DATE, PropertyCardinality.SINGLE));
            linkPropertyRepository.associate(productCoverLinkDefinition.id(), createDatePropertyDefinition.id());

            var productCoverLinkPerspective = new ItemLinkPerspectiveDefinition(productDefinition.id(), productCoverLinkDefinition.id(),"cover",  0, 1);
            linkPerspectiveDefinitionRepository.create(productCoverLinkPerspective);

            var coverProductLinkPerspective = new ItemLinkPerspectiveDefinition(coverDefinition.id(), productCoverLinkDefinition.id(), "product", 1, 1);
            linkPerspectiveDefinitionRepository.create(coverProductLinkPerspective);

        }
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
                            .map(this::mapToModel)
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
                                        : linkProperties.stream().map(this::mapToModel).toList();
                                return Map.entry(perspective.name(), new ItemLinkPerspectiveModel(inverseItem.name(), perspective.minCardinality(), perspective.maxCardinality(), linkPropertyModels));
                            })
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey,
                                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                            ));

            return new ItemDefinitionModel(itemDefinition.id(), itemDefinition.name(), propertyModels, links);
        }).toList();

        return new SchemaModel(itemDefinitionDtos);
    }

    private PropertyDefinitionModel mapToModel(IdentifiedPropertyDefinition propertyDef) {
        return new PropertyDefinitionModel(propertyDef.id(), propertyDef.name(), propertyDef.type(), propertyDef.cardinality());
    }

}
