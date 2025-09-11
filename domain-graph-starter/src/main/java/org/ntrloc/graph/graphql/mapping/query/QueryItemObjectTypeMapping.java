package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Selection;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class QueryItemObjectTypeMapping implements ObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(QueryItemObjectTypeMapping.class);

    private static final String ID_FIELD_NAME = "id";
    private static final String IS_LATEST_VERSION_FIELD_NAME = "isLatestVersion";
    private static final String LINKS_FIELD_NAME = "links";
    private static final String PROPERTIES_FIELD_NAME = "properties";
    private static final String VERSION_FIELD_NAME = "version";

    private String graphQlTypeName;
    private ItemDefinition itemDefinition;
    private QueryItemPropertyObjectTypeMapping propertyObjectTypeMapping;

    public QueryItemObjectTypeMapping(ItemDefinition itemDefinition, Set<LinkDefinition> linkDefinitions) {
        this.itemDefinition = itemDefinition;
        String typeName = "%s".formatted(itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        propertyObjectTypeMapping = new QueryItemPropertyObjectTypeMapping(itemDefinition);
    }

    public ItemDefinition getItemDefinition() {
        return itemDefinition;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {

        List<ObjectTypeDefinition> retList = new java.util.ArrayList<>();
        retList.addAll(propertyObjectTypeMapping.getObjectTypeDefinitions());

        Optional<ObjectTypeDefinition> typeDefOptional = retList.stream()
                .filter(def -> Boolean.parseBoolean(def.getAdditionalData().getOrDefault(QueryItemPropertyObjectTypeMapping.IS_PROPERTY_TYPE_FLAG, Boolean.valueOf(false).toString())))
                .findFirst();

        List<FieldDefinition> fieldDefinitions = new java.util.ArrayList<>();
        typeDefOptional.ifPresent(def -> {
            var propertiesDef = FieldDefinition.newFieldDefinition()
                    .name(PROPERTIES_FIELD_NAME)
                    .type(new TypeName(typeDefOptional.get().getName()))
                    .build();
            fieldDefinitions.add(propertiesDef);
        });

        FieldDefinition idField = FieldDefinition.newFieldDefinition()
                .name(ID_FIELD_NAME)
                .type(new TypeName("String"))
                .build();
        fieldDefinitions.add(idField);

        FieldDefinition versionField = FieldDefinition.newFieldDefinition()
                .name(VERSION_FIELD_NAME)
                .type(new TypeName("Int"))
                .build();
        fieldDefinitions.add(versionField);

        FieldDefinition isLatestVersionField = FieldDefinition.newFieldDefinition()
                .name(IS_LATEST_VERSION_FIELD_NAME)
                .type(new TypeName("Boolean"))
                .build();
        fieldDefinitions.add(isLatestVersionField);

        var itemQueryType = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(fieldDefinitions)
                .build();

        retList.add(itemQueryType);

        return retList;
    }

    SelectableItemProjectionSpec parseQueryField(Field field) {
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(itemDefinition.getName());
        List<Selection> selections = field.getSelectionSet().getSelections();
        LOG.info("Parsing selections {}", selections);

        var propertySelectionOpt = selections.stream()
                .filter(s -> s instanceof Field)
                .map(s -> (Field) s)
                .filter(f -> f.getName().equals(PROPERTIES_FIELD_NAME)).findFirst();
        if (propertySelectionOpt.isPresent()) {
            var propertiesField = propertySelectionOpt.get();
            List<String> properties = propertyObjectTypeMapping.parseQueryProperties(propertiesField);
            spec.setProperties(properties);
        }

        return spec;
    }

}
