package org.ntrloc.graph.graphql.impl;

import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class GraphqlDefinitions {

    private List<ObjectTypeDefinition> entityObjectTypeDefinitions = new ArrayList<>();

    private List<ObjectTypeDefinition> objectTypeDefinitions = new ArrayList<>();

    private List<InputObjectTypeDefinition> inputObjectTypeDefinitions;

    private List<ObjectTypeExtensionDefinition> objectTypeExtensionDefinitions = new ArrayList<>();

    private List<DirectiveDefinition> directiveDefinitions = new ArrayList<>();

    public GraphqlDefinitions() {
        // no-op
    }

    public void addDirectiveDefinition(DirectiveDefinition def) {
        directiveDefinitions.add(def);
    }

    public void addEntityObjectTypeDefinition(ObjectTypeDefinition def) {
        entityObjectTypeDefinitions.add(def);
    }

    public void addObjectTypeDefinition(ObjectTypeDefinition def) {
        objectTypeDefinitions.add(def);
    }

    public void addObjectTypeExtensionDefinition(ObjectTypeExtensionDefinition def) {
        objectTypeExtensionDefinitions.add(def);
    }

    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        return Stream.concat(entityObjectTypeDefinitions.stream(), objectTypeDefinitions.stream()).toList();
    }

    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return inputObjectTypeDefinitions == null ? List.of() : inputObjectTypeDefinitions;
    }

    public List<ObjectTypeExtensionDefinition> getObjectTypeExtensionDefinitions() {
        return objectTypeExtensionDefinitions == null ? List.of() : objectTypeExtensionDefinitions;
    }

    public List<DirectiveDefinition> getDirectiveDefinitions() {
        return directiveDefinitions;
    }

}
