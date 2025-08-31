package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.mutation.EntityCreateMutation;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/* Maps an entity to a GraphQL input object that represents a create instruction. */
public class EntityCreateInputObjectTypeMapping implements InputObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EntityCreateInputObjectTypeMapping.class);

    private static final String LINKS_FIELD_NAME = "links";
    private static final String REF_FIELD_NAME = "ref";
    private static final String PROPERTIES_FIELD_NAME = "properties";

    private String graphQlTypeName;
    private EntityDefinition entityDefinition;
    private EntityPropertiesInputObjectTypeMapping propertiesMapping;
    private EntityCreateLinksInputObjectTypeMapping linkCreateInputType;

    public EntityCreateInputObjectTypeMapping(EntityDefinition entityDefinition, EntityPropertiesInputObjectTypeMapping propertiesMapping) {
        String typeName = String.format("%s Create Input", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.entityDefinition = entityDefinition;
        this.propertiesMapping = propertiesMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public EntityDefinition getEntityDefinition() {
        return entityDefinition;
    }

    public void setLinkCreateInputType(EntityCreateLinksInputObjectTypeMapping linkCreateInputType) {
        this.linkCreateInputType = linkCreateInputType;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputValueDefinition> entityCreateInputValues = new ArrayList<>();
        InputValueDefinition referenceValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name(REF_FIELD_NAME)
                .type(new TypeName("String"))
                .build();

        InputValueDefinition entityPropertiesInputValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name(PROPERTIES_FIELD_NAME)
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();

        entityCreateInputValues.addAll(List.of(referenceValueDefinition, entityPropertiesInputValueDefinition));

        if (linkCreateInputType != null) {
            entityCreateInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name(LINKS_FIELD_NAME)
                    .type(new ListType(new NonNullType(new TypeName(linkCreateInputType.getGraphQlTypeName()))))
                    .build());
        }

        var entityCreateType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(entityCreateInputValues)
                .build();

        return Stream.of(Stream.of(entityCreateType), propertiesMapping.getInputObjectTypeDefinitions().stream(), linkCreateInputType.getInputObjectTypeDefinitions().stream()).flatMap(s -> s).toList();
    }

    public EntityCreateMutation parseCreateMutation(ObjectValue objectValue) {
        Map<String, ObjectField> objectFieldMap = objectValue.getObjectFields().stream().collect(java.util.stream.Collectors.toMap(ObjectField::getName, f -> f));
        EntityCreateMutation mutation = new EntityCreateMutation();
        if (objectFieldMap.containsKey(PROPERTIES_FIELD_NAME)) {
            LOG.info("I need to map properties {}", objectFieldMap.get(PROPERTIES_FIELD_NAME));
            List<? extends Property> properties = propertiesMapping.mapProperties((ObjectValue) objectFieldMap.get(PROPERTIES_FIELD_NAME).getValue());
            mutation.setProperties(properties);
        }
        return mutation;
    }

}
