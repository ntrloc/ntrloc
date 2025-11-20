package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Selection;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.LinkProjection;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.language.projection.SpecificLinksProjectionSpec;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ItemLinksObjectTypeMapping implements ObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ItemLinksObjectTypeMapping.class);

    static final String IS_LINK_TYPE_FLAG = "isLinkType";

    private final String graphQlTypeName;
    private final ItemDefinition itemDefinition;

    private final Map<String, ItemLinkObjectTypeMapping> linkObjectTypeMappings = new HashMap<>();
    private final Map<String, ItemLinkObjectTypeMapping> linkObjectTypeMappingsBySchemaName = new HashMap<>();

    ItemLinksObjectTypeMapping(ItemDefinition itemDefinition, Set<LinkDefinition> linkDefinitions) {
        this.itemDefinition = itemDefinition;
        String typeName = "%s Links".formatted(itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        Set<LinkDefinition> outgoingLinks = linkDefinitions.stream().filter(def -> def.getSourceItemType().equals(itemDefinition.getName())).collect(Collectors.toSet());
        Set<ItemOutgoingLinkObjectTypeMapping> outMappings = outgoingLinks.stream().map(ItemOutgoingLinkObjectTypeMapping::new).collect(Collectors.toSet());
        linkObjectTypeMappings.putAll(outMappings.stream().collect(Collectors.toMap(ItemOutgoingLinkObjectTypeMapping::getLinkGraphQlFieldName, mapping -> mapping)));
        linkObjectTypeMappingsBySchemaName.putAll(outMappings.stream().collect(Collectors.toMap(ItemOutgoingLinkObjectTypeMapping::getSourceLabel, mapping -> mapping)));

        Set<LinkDefinition> incomingLinks = linkDefinitions.stream().filter(def -> def.getTargetItemType().equals(itemDefinition.getName())).collect(Collectors.toSet());
        Set<ItemIncomingLinkObjectTypeMapping> inMappings = incomingLinks.stream().map(ItemIncomingLinkObjectTypeMapping::new).collect(Collectors.toSet());
        linkObjectTypeMappings.putAll(inMappings.stream().collect(Collectors.toMap(ItemIncomingLinkObjectTypeMapping::getLinkGraphQlFieldName, mapping -> mapping)));
        linkObjectTypeMappingsBySchemaName.putAll(inMappings.stream().collect(Collectors.toMap(ItemIncomingLinkObjectTypeMapping::getTargetLabel, mapping -> mapping)));
    }

    void registerItemTypeMappingsForLinks(Map<String, ItemObjectTypeMapping> mappings) {
        for (ItemLinkObjectTypeMapping linkMapping : linkObjectTypeMappings.values()) {
            linkMapping.registerRelatedItemType(mappings);
        }
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        List<ObjectTypeDefinition> retList = new ArrayList<>();
        retList.addAll(linkObjectTypeMappings.values().stream().map(ItemLinkObjectTypeMapping::getObjectTypeDefinitions).flatMap(List::stream).toList());

        List<FieldDefinition> linkFields = new ArrayList<>();
        linkObjectTypeMappings.forEach((name, mapping) -> {
            FieldDefinition fd = FieldDefinition.newFieldDefinition()
                    .name(name)
                    .type(new ListType(new TypeName(mapping.getGraphQlTypeName())))
                    .build();
            linkFields.add(fd);
        });

        ObjectTypeDefinition linksObjectDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(linkFields)
                .additionalData(IS_LINK_TYPE_FLAG, Boolean.toString(true))
                .build();
        retList.add(linksObjectDefinition);

        return retList;
    }

    SpecificLinksProjectionSpec parseQueryLinks(Field field) {
        LOG.info("parsing query links field {}", field);
        Set<LinkProjectionSpec> linkProjectionSpecs = new HashSet<>();
        List<Selection> selections = field.getSelectionSet().getSelections();
        List<Field> selectionFields = selections.stream().filter(s -> s instanceof Field).map(s -> (Field) s).toList();
        for (Field selectionField : selectionFields) {
            ItemLinkObjectTypeMapping linkMapping = linkObjectTypeMappings.get(selectionField.getName());
            LinkProjectionSpec lps = linkMapping.parseLinkProjectionSpec(selectionField);
            linkProjectionSpecs.add(lps);
        }
        return new SpecificLinksProjectionSpec(linkProjectionSpecs);
    }

    ItemProjection translateItemProjection(ItemProjection itemProjection) {
        Map<String, List<LinkProjection>> links = itemProjection.getLinks();
        Map<String, List<LinkProjection>> transformedLinks = new HashMap<>();
        for (Map.Entry<String, List<LinkProjection>> entry : links.entrySet()) {
            String linkLabel = entry.getKey();
            List<LinkProjection> linkProjections = entry.getValue();
            ItemLinkObjectTypeMapping linkMapping = linkObjectTypeMappingsBySchemaName.get(linkLabel);
            String linkGraphQlName = linkMapping.getLinkGraphQlFieldName();
            List<LinkProjection> updatedLinks = linkMapping.translateLinkProjections(linkProjections);
            transformedLinks.put(linkGraphQlName, updatedLinks);
        }
        itemProjection.setLinks(transformedLinks);
        return itemProjection;
    }

}
