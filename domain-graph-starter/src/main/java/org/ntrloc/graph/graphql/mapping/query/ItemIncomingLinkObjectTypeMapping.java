package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Selection;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ItemIncomingLinkObjectTypeMapping extends ItemLinkObjectTypeMapping implements ObjectTypeProducer {

    private String sourceFieldName = "source";

    private final String graphQlTypeName;
    private final LinkDefinition linkDefinition;
    private final ItemLinkPropertiesObjectTypeMapping propertiesObjectTypeMapping;

    ItemIncomingLinkObjectTypeMapping(LinkDefinition linkDefinition) {
        String typeName = "%s %s %s Link".formatted(linkDefinition.getTargetEntity(), linkDefinition.getTargetLabel(), linkDefinition.getSourceEntity());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.linkDefinition = linkDefinition;
        this.propertiesObjectTypeMapping = new ItemLinkPropertiesObjectTypeMapping(linkDefinition);
    }

    @Override
    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    void registerRelatedItemType(Map<String, ItemObjectTypeMapping> mappings) {
        this.relatedItemObjectTypeMapping = mappings.get(linkDefinition.getSourceEntity());
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        List<ObjectTypeDefinition> retList = new ArrayList<>();
        retList.addAll(propertiesObjectTypeMapping.getObjectTypeDefinitions());

        List<FieldDefinition> fieldDefinitions = new ArrayList<>();

        Optional<ObjectTypeDefinition> propertyTypeDefOpt = retList.stream()
                .filter(def -> Boolean.parseBoolean(def.getAdditionalData().getOrDefault(ItemLinkPropertiesObjectTypeMapping.IS_LINK_PROPERTIES_TYPE, Boolean.toString(false))))
                .findFirst();
        if (propertyTypeDefOpt.isPresent()) {
            ObjectTypeDefinition propertyTypeDef = propertyTypeDefOpt.get();
            FieldDefinition fd = FieldDefinition.newFieldDefinition()
                    .name(propertiesFieldName)
                    .type(new TypeName(propertyTypeDef.getName()))
                    .build();
            fieldDefinitions.add(fd);
        }
        FieldDefinition sourceField = FieldDefinition.newFieldDefinition()
                .name(sourceFieldName)
                .type(new TypeName(ItemObjectTypeMapping.getItemGraphQlTypeName(linkDefinition.getSourceEntity())))
                .build();
        fieldDefinitions.add(sourceField);

        ObjectTypeDefinition outgoingLinkType = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(fieldDefinitions)
                .build();
        retList.add(outgoingLinkType);

        return retList;
    }

    String getLinkGraphQlFieldName() {
        return CaseUtils.toCamelCase("%s".formatted(linkDefinition.getTargetLabel()), false, '_', '-');
    }

    @Override
    LinkProjectionSpec parseLinkProjectionSpec(Field field) {
        List<Selection> selections = field.getSelectionSet().getSelections();
        List<Field> selectionFields = selections.stream().filter(s -> s instanceof Field).map(s -> (Field) s).toList();
        LinkProjectionSpec spec = new LinkProjectionSpec(linkDefinition.getName(), Direction.IN, linkDefinition.getSourceEntity());

        Optional<Field> propertiesField = selectionFields.stream().filter(f -> f.getName().equals(propertiesFieldName)).findFirst();
        if (propertiesField.isPresent()) {
            // TODO
        }

        Optional<Field> sourceField = selectionFields.stream().filter(f -> f.getName().equals(sourceFieldName)).findFirst();
        if (sourceField.isPresent()) {
            var itemProjection = this.relatedItemObjectTypeMapping.parseQueryItem(sourceField.get());
            spec.setItemProjectionSpec(itemProjection);
        }

        return spec;
    }
}
