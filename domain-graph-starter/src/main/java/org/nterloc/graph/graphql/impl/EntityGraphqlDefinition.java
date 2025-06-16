package org.nterloc.graph.graphql.impl;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;

import java.util.List;

public class EntityGraphqlDefinition {

    private ObjectTypeDefinition entityDefinition;

    private InputObjectTypeDefinition entityInputObjectDefinition;

    private List<ObjectTypeDefinition> entityGroupDefinitions;

    private List<InputObjectTypeDefinition> entityGroupInputObjectDefinitions;

    public EntityGraphqlDefinition(ObjectTypeDefinition entityDefinition, List<ObjectTypeDefinition> entityGroupDefinitions, InputObjectTypeDefinition inputObjectTypeDefinition, List<InputObjectTypeDefinition> groupInputObjectDefinitions) {
        this.entityDefinition = entityDefinition;
        this.entityInputObjectDefinition = inputObjectTypeDefinition;
        this.entityGroupDefinitions = entityGroupDefinitions;
        this.entityGroupInputObjectDefinitions = groupInputObjectDefinitions;
    }

    public ObjectTypeDefinition getEntityDefinition() {
        return entityDefinition;
    }

    public InputObjectTypeDefinition getEntityInputObjectDefinition() {
        return entityInputObjectDefinition;
    }

    public List<ObjectTypeDefinition> getEntityGroupDefinitions() {
        return entityGroupDefinitions;
    }

    public List<InputObjectTypeDefinition> getEntityGroupInputObjectDefinitions() {
        return entityGroupInputObjectDefinitions;
    }
}
