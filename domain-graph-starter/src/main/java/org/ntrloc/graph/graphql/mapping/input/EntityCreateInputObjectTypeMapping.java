package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/* Maps an entity to a GraphQL input object that represents a create instruction. */
public class EntityCreateInputObjectTypeMapping implements InputObjectTypeProducer {

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
                .name("ref")
                .type(new TypeName("String"))
                .build();

        InputValueDefinition entityPropertiesInputValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name("properties")
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();

        entityCreateInputValues.addAll(List.of(referenceValueDefinition, entityPropertiesInputValueDefinition));

        if (linkCreateInputType != null) {
            entityCreateInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name("links")
                    .type(new ListType(new NonNullType(new TypeName(linkCreateInputType.getGraphQlTypeName()))))
                    .build());
        }

        var entityCreateType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(entityCreateInputValues)
                .build();

        return Stream.of(Stream.of(entityCreateType), propertiesMapping.getInputObjectTypeDefinitions().stream(), linkCreateInputType.getInputObjectTypeDefinitions().stream()).flatMap(s -> s).toList();
    }
}
