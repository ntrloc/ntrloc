package org.ntrloc.graph.graphql.impl;

import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;

import java.util.ArrayList;
import java.util.List;

public class GraphqlDefinitions {

    private List<ObjectTypeDefinition> objectTypeDefinitions;

    private List<InputObjectTypeDefinition> inputObjectTypeDefinitions;

    private List<ObjectTypeExtensionDefinition> objectTypeExtensionDefinitions;

    private List<DirectiveDefinition> directiveDefinitions;

    public GraphqlDefinitions() {
        // no-op
    }

    public GraphqlDefinitions objectTypes(List<ObjectTypeDefinition> objectTypeDefinitions) {
        this.objectTypeDefinitions = objectTypeDefinitions;
        return this;
    }

    public GraphqlDefinitions inputObjectTypes(List<InputObjectTypeDefinition> inputObjectTypeDefinitions) {
        this.inputObjectTypeDefinitions = inputObjectTypeDefinitions;
        return this;
    }

    public GraphqlDefinitions objectTypeExtensions(List<ObjectTypeExtensionDefinition> objectTypeExtensionDefinitions) {
        this.objectTypeExtensionDefinitions = objectTypeExtensionDefinitions;
        return this;
    }

    public GraphqlDefinitions directiveDefinitions(List<DirectiveDefinition> directiveDefinitions) {
        this.directiveDefinitions = directiveDefinitions;
        return this;
    }

    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        return objectTypeDefinitions == null ? List.of() : objectTypeDefinitions;
    }

    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return inputObjectTypeDefinitions == null ? List.of() : inputObjectTypeDefinitions;
    }

    public List<ObjectTypeExtensionDefinition> getObjectTypeExtensionDefinitions() {
        return objectTypeExtensionDefinitions == null ? List.of() : objectTypeExtensionDefinitions;
    }

    public List<DirectiveDefinition> getDirectiveDefinitions() {
        return directiveDefinitions == null ? List.of() : directiveDefinitions;
    }

    public GraphqlDefinitions merge(GraphqlDefinitions other) {
        List<ObjectTypeDefinition> objectTypeDefinitions = new ArrayList<>();
        if (this.objectTypeDefinitions != null) {
            objectTypeDefinitions.addAll(this.objectTypeDefinitions);
        }
        if (other.objectTypeDefinitions != null) {
            objectTypeDefinitions.addAll(other.objectTypeDefinitions);
        }

        List<InputObjectTypeDefinition> inputObjectTypeDefinitions = new ArrayList<>();
        if (this.inputObjectTypeDefinitions != null) {
            inputObjectTypeDefinitions.addAll(this.inputObjectTypeDefinitions);
        }
        if (other.inputObjectTypeDefinitions != null) {
            inputObjectTypeDefinitions.addAll(other.inputObjectTypeDefinitions);
        }

        List<ObjectTypeExtensionDefinition> objectTypeExtensionDefinitions = new ArrayList<>();
        if (this.objectTypeExtensionDefinitions != null) {
            objectTypeExtensionDefinitions.addAll(this.objectTypeExtensionDefinitions);
        }
        if (other.objectTypeExtensionDefinitions != null) {
            objectTypeExtensionDefinitions.addAll(other.objectTypeExtensionDefinitions);
        }

        List<DirectiveDefinition> directiveDefinitions = new ArrayList<>();
        if (this.directiveDefinitions != null) {
            directiveDefinitions.addAll(this.directiveDefinitions);
        }
        if (other.directiveDefinitions != null) {
            directiveDefinitions.addAll(other.directiveDefinitions);
        }

        return new GraphqlDefinitions()
                .objectTypes(objectTypeDefinitions)
                .inputObjectTypes(inputObjectTypeDefinitions)
                .objectTypeExtensions(objectTypeExtensionDefinitions)
                .directiveDefinitions(directiveDefinitions);
    }

}
