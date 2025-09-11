package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;

public class MutationResultObjectTypeMapping implements ObjectTypeProducer {

    private String graphQlTypeName;
    private MutationResultItemObjectTypeMapping mutationResultItemObjectTypeMapping = new MutationResultItemObjectTypeMapping();

    public MutationResultObjectTypeMapping() {
        String typeName = "Mutation Result";
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
                        FieldDefinition.newFieldDefinition().name("created").type(new ListType(new NonNullType(new TypeName(mutationResultItemObjectTypeMapping.getGraphQlTypeName())))).build(),
                        FieldDefinition.newFieldDefinition().name("updated").type(new ListType(new NonNullType(new TypeName(mutationResultItemObjectTypeMapping.getGraphQlTypeName())))).build(),
                        FieldDefinition.newFieldDefinition().name("deleted").type(new ListType(new NonNullType(new TypeName(mutationResultItemObjectTypeMapping.getGraphQlTypeName())))).build()
                ))
                .build();
        List<ObjectTypeDefinition> retList = new ArrayList<>();
        retList.addAll(mutationResultItemObjectTypeMapping.getObjectTypeDefinitions());
        retList.add(def);
        return retList;
    }

}
