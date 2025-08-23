package org.ntrloc.graph.graphql.impl;

import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class GraphqlDefinitions {

    private List<ObjectTypeDefinition> entityObjectTypeDefinitions = new ArrayList<>();

    private Map<String, ObjectTypeDefinition> objectTypeDefinitions = new HashMap<>();

    private Map<String, InputObjectTypeDefinition> inputObjectTypeDefinitionMap = new HashMap<>();

    private List<ObjectTypeExtensionDefinition> objectTypeExtensionDefinitions = new ArrayList<>();

    private List<DirectiveDefinition> directiveDefinitions = new ArrayList<>();

    public GraphqlDefinitions() {
        // no-op
    }

    // -------------- Directive definitions ---------------

    public void addDirectiveDefinition(DirectiveDefinition def) {
        directiveDefinitions.add(def);
    }

    public List<DirectiveDefinition> getDirectiveDefinitions() {
        return directiveDefinitions;
    }

    // -------------- Object type definitions ---------------

    public void addEntityObjectTypeDefinition(ObjectTypeDefinition def) {
        entityObjectTypeDefinitions.add(def);
    }

    public void addObjectTypeDefinition(ObjectTypeDefinition def) {
        objectTypeDefinitions.put(def.getName(), def);
    }

    public boolean containsObjectTypeDefinition(String name) {
        return objectTypeDefinitions.containsKey(name);
    }

    public ObjectTypeDefinition getObjectTypeDefinition(String name) {
        return objectTypeDefinitions.get(name);
    }

    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        return Stream.concat(entityObjectTypeDefinitions.stream(), objectTypeDefinitions.values().stream()).toList();
    }

    // -------------- Object type extension definitions ---------------

    public void addObjectTypeExtensionDefinition(ObjectTypeExtensionDefinition def) {
        objectTypeExtensionDefinitions.add(def);
    }

    public List<ObjectTypeExtensionDefinition> getObjectTypeExtensionDefinitions() {
        return objectTypeExtensionDefinitions == null ? List.of() : objectTypeExtensionDefinitions;
    }

    // -------------- Input object type definitions ---------------

    public void addInputObjectTypeDefinition(InputObjectTypeDefinition def) {
        inputObjectTypeDefinitionMap.put(def.getName(), def);
    }

    public boolean containsInputObjectTypeDefinition(String name) {
        return inputObjectTypeDefinitionMap.containsKey(name);
    }

    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return inputObjectTypeDefinitionMap.values().stream().toList();
    }

}
