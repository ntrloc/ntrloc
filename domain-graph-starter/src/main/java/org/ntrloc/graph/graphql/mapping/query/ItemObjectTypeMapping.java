package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Selection;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.ItemProjectionSpec;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.language.selectors.ItemTypeSelector;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ItemObjectTypeMapping implements ObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ItemObjectTypeMapping.class);

    // TODO the implicit properties should be derived from PropertyConstants!
    private static final String ID_FIELD_NAME = "id";
    private static final String IS_LATEST_VERSION_FIELD_NAME = "isLatestVersion";
    private static final String ITEM_TYPE_FIELD_NAME = "itemType";
    private static final String LINKS_FIELD_NAME = "links";
    private static final String PROPERTIES_FIELD_NAME = "properties";
    private static final String VERSION_FIELD_NAME = "version";
    private static final String COMMIT_ID_FIELD_NAME = "commitId";

    private static final List<String> IMPLICIT_PROPERTIES = List.of(COMMIT_ID_FIELD_NAME, ITEM_TYPE_FIELD_NAME, VERSION_FIELD_NAME, IS_LATEST_VERSION_FIELD_NAME);

    private final String graphQlTypeName;
    private final ItemDefinition itemDefinition;
    private final ItemPropertiesObjectTypeMapping propertyObjectTypeMapping;
    private final ItemLinksObjectTypeMapping linksObjectTypeMapping;

    public ItemObjectTypeMapping(ItemDefinition itemDefinition, Set<LinkDefinition> linkDefinitions) {
        this.itemDefinition = itemDefinition;
        this.graphQlTypeName = getItemGraphQlTypeName(itemDefinition.getName());
        propertyObjectTypeMapping = new ItemPropertiesObjectTypeMapping(itemDefinition);
        linksObjectTypeMapping = new ItemLinksObjectTypeMapping(itemDefinition, linkDefinitions);
    }

    static String getItemGraphQlTypeName(String itemType) {
        String typeName = "%s".formatted(itemType);
        return CaseUtils.toCamelCase(typeName, true, '_', '-');
    }

    public ItemDefinition getItemDefinition() {
        return itemDefinition;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    void registerItemTypeMappingsForLinks(Map<String, ItemObjectTypeMapping> itemTypeMappings) {
        linksObjectTypeMapping.registerItemTypeMappingsForLinks(itemTypeMappings);
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {

        List<ObjectTypeDefinition> retList = new java.util.ArrayList<>();
        retList.addAll(propertyObjectTypeMapping.getObjectTypeDefinitions());
        retList.addAll(linksObjectTypeMapping.getObjectTypeDefinitions());

        Optional<ObjectTypeDefinition> typeDefOptional = retList.stream()
                .filter(def -> Boolean.parseBoolean(def.getAdditionalData().getOrDefault(ItemPropertiesObjectTypeMapping.IS_PROPERTY_TYPE_FLAG, Boolean.valueOf(false).toString())))
                .findFirst();

        List<FieldDefinition> fieldDefinitions = new java.util.ArrayList<>();
        typeDefOptional.ifPresent(def -> {
            var propertiesDef = FieldDefinition.newFieldDefinition()
                    .name(PROPERTIES_FIELD_NAME)
                    .type(new TypeName(typeDefOptional.get().getName()))
                    .build();
            fieldDefinitions.add(propertiesDef);
        });

        // TODO the implicit field definitions should be derived from PropertyConstants, not hardcoded like this!

        FieldDefinition commitIdField = FieldDefinition.newFieldDefinition()
                .name(COMMIT_ID_FIELD_NAME)
                .type(new TypeName("String"))
                .build();
        fieldDefinitions.add(commitIdField);

        FieldDefinition idField = FieldDefinition.newFieldDefinition()
                .name(ID_FIELD_NAME)
                .type(new TypeName("String"))
                .build();
        fieldDefinitions.add(idField);

        FieldDefinition itemTypeField = FieldDefinition.newFieldDefinition()
                .name(ITEM_TYPE_FIELD_NAME)
                .type(new TypeName("String"))
                .build();
        fieldDefinitions.add(itemTypeField);

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

        Optional<ObjectTypeDefinition> linkTypeDefOptional = retList.stream()
                .filter(def -> Boolean.parseBoolean(def.getAdditionalData().getOrDefault(ItemLinksObjectTypeMapping.IS_LINK_TYPE_FLAG, Boolean.toString(false))))
                .findFirst();
        if (linkTypeDefOptional.isPresent()) {
            var linksDef = FieldDefinition.newFieldDefinition()
                    .name(LINKS_FIELD_NAME)
                    .type(new TypeName(linkTypeDefOptional.get().getName()))
                    .build();
            fieldDefinitions.add(linksDef);
        }

        var itemQueryType = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(fieldDefinitions)
                .build();

        retList.add(itemQueryType);

        return retList;
    }

    SelectableItemProjectionSpec parseSelectableQueryField(Field field) {
        var spec = new SelectableItemProjectionSpec(new ItemTypeSelector(itemDefinition.getName()));
        populateSpec(spec, field);
        return spec;
    }

    ItemProjectionSpec parseQueryItem(Field field) {
        var spec = new ItemProjectionSpec();
        populateSpec(spec, field);
        return spec;
    }

    private void populateSpec(ItemProjectionSpec spec, Field field) {
        List<Selection> selections = field.getSelectionSet().getSelections();
        LOG.debug("Parsing selections {}", selections);

        selections.stream()
                .filter(s -> s instanceof Field)
                .map(s -> (Field) s)
                .filter(f -> !IMPLICIT_PROPERTIES.contains(f.getName()))
                .forEach(f -> {
                    switch (f.getName()) {
                        case PROPERTIES_FIELD_NAME -> {
                            List<String> properties = propertyObjectTypeMapping.parseQueryProperties(f);
                            spec.setProperties(properties);
                        }
                        case LINKS_FIELD_NAME -> {
                            var linkProjectionSpec = linksObjectTypeMapping.parseQueryLinks(f);
                            spec.setLinks(linkProjectionSpec);
                        }
                        default -> {
                            throw new IllegalArgumentException("Unknown field name %s".formatted(f.getName()));
                        }
                    }
                });
    }

    ItemProjection translateItemProjection(ItemProjection itemProjection) {
        itemProjection.setItemType(this.graphQlTypeName);
        if (itemProjection.getProperties() != null) {
            propertyObjectTypeMapping.translateItemProjection(itemProjection);
        }
        if (itemProjection.getLinks() != null) {
            linksObjectTypeMapping.translateItemProjection(itemProjection);
        }
        return itemProjection;
    }

}