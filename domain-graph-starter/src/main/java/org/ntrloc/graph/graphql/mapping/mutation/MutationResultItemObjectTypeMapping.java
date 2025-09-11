package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.List;

public class MutationResultItemObjectTypeMapping implements ObjectTypeProducer {

    private String graphQlTypeName;

    public MutationResultItemObjectTypeMapping() {
        String typeName = "Mutation Result Item";
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        ObjectTypeDefinition def = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(List.of(
                        FieldDefinition.newFieldDefinition().name("id").type(new TypeName("String")).build(),
                        FieldDefinition.newFieldDefinition().name("entityType").type(new TypeName("String")).build()
                ))
                .build();

        return List.of(def);
    }

}
