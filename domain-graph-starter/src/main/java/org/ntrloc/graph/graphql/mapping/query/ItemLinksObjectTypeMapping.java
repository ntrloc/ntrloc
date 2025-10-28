package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Selection;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.language.projection.SpecificLinksProjectionSpec;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ItemLinksObjectTypeMapping implements ObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ItemLinksObjectTypeMapping.class);

    static final String IS_LINK_TYPE_FLAG = "isLinkType";

    private final String graphQlTypeName;
    private final ItemDefinition itemDefinition;

    private final Map<String, ItemLinkObjectTypeMapping> linkObjectTypeMappings;

    ItemLinksObjectTypeMapping(ItemDefinition itemDefinition, Set<LinkDefinition> linkDefinitions) {
        this.itemDefinition = itemDefinition;
        String typeName = "%s Links".formatted(itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        linkObjectTypeMappings = new HashMap<>();

        Set<LinkDefinition> outgoingLinks = linkDefinitions.stream().filter(def -> def.getSourceItemType().equals(itemDefinition.getName())).collect(Collectors.toSet());
        linkObjectTypeMappings.putAll(outgoingLinks.stream()
                .map(ItemOutgoingLinkObjectTypeMapping::new)
                .collect(Collectors.toMap(ItemOutgoingLinkObjectTypeMapping::getLinkGraphQlFieldName, mapping -> mapping)));

        Set<LinkDefinition> incomingLinks = linkDefinitions.stream().filter(def -> def.getTargetItemType().equals(itemDefinition.getName())).collect(Collectors.toSet());
        linkObjectTypeMappings.putAll(incomingLinks.stream()
                .map(ItemIncomingLinkObjectTypeMapping::new)
                .collect(Collectors.toMap(ItemIncomingLinkObjectTypeMapping::getLinkGraphQlFieldName, mapping -> mapping)));
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
        Map<String, LinkProjectionSpec> retMap = new HashMap<>();
        List<Selection> selections = field.getSelectionSet().getSelections();
        List<Field> selectionFields = selections.stream().filter(s -> s instanceof Field).map(s -> (Field) s).toList();
        for (Field selectionField : selectionFields) {
            ItemLinkObjectTypeMapping linkMapping = linkObjectTypeMappings.get(selectionField.getName());
            LOG.info("found link mapping {}", linkMapping);
            LinkProjectionSpec lps = linkMapping.parseLinkProjectionSpec(selectionField);
            retMap.put(selectionField.getName(), lps);
        }
        return new SpecificLinksProjectionSpec(retMap);
    }

}
