package org.ntrloc.graph.graphql;

import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.language.TypeName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.impl.GraphQLSchemaGeneratorImpl;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphQLSchemaGeneratorTest {

    private GraphQLSchemaGenerator generator;

    Set<EntityDefinition> entityDefinitions = new HashSet<>();
    Set<RelationshipDefinition> relationshipDefinitions = new HashSet<>();

    @BeforeEach
    void init() {
        generator = new GraphQLSchemaGeneratorImpl();

        EntityDefinition photoEntity = new EntityDefinition();
        photoEntity.setName("Photo");
        photoEntity.setDescription("A photo");

        PropertyDefinition photoName = new PropertyDefinition("name", PropertyType.STRING, "photo name");
        PropertyDefinition photoNumber = new PropertyDefinition("number", PropertyType.INT, "photo number");
        photoEntity.setProperties(Set.of(photoName, photoNumber));

        PropertyDefinition title1 = new PropertyDefinition("title1", PropertyType.STRING, "title 1");
        PropertyDefinition title2 = new PropertyDefinition("title2", PropertyType.STRING, "title 2");

        PropertyGroupDefinition titleGroup = new PropertyGroupDefinition("Titles", "photo titless", Set.of(title1, title2));
        photoEntity.setPropertyGroups(Set.of(titleGroup));

        entityDefinitions.add(photoEntity);

        EntityDefinition photographerEntity = new EntityDefinition();
        photographerEntity.setName("Photographer");
        photographerEntity.setDescription("A photographer");
        PropertyDefinition photographerName = new PropertyDefinition("name", PropertyType.STRING, "photographer name");
        photographerEntity.setProperties(Set.of(photographerName));

        entityDefinitions.add(photographerEntity);

        RelationshipDefinition photoRelationship = new RelationshipDefinition();
        photoRelationship.setSourceEntity("Photographer");
        photoRelationship.setTargetEntity("Photo");
        photoRelationship.setName("CREATED");
        photoRelationship.setSourceLabel("created");
        photoRelationship.setTargetLabel("creator");

        PropertyDefinition createdCountProperty = new PropertyDefinition("count", PropertyType.INT, "count");
        photoRelationship.setProperties(Set.of(createdCountProperty));

        relationshipDefinitions.add(photoRelationship);
    }

    @Test
    @DisplayName("Generate GraphQL types for entities")
    void testGenerateObjectTypes() {
        var typeDefinitions = generator.generateTypeDefinitions(entityDefinitions, relationshipDefinitions);
        assertNotNull(typeDefinitions, "null type definitions");

        List<ObjectTypeDefinition> objectTypeDefinitions = typeDefinitions.getObjectTypeDefinitions();

        // verify the object type and fields were created for Photo
        Optional<ObjectTypeDefinition> photoType = findObjectTypeDefinition(objectTypeDefinitions, "Photo");
        assertTrue(photoType.isPresent(), "photo type not found");
        ObjectTypeDefinition photoTypeDef = photoType.get();

        List<Directive> directives = photoTypeDef.getDirectives(GraphQLSchemaGeneratorImpl.ENTITY_TYPE_DIRECTIVE_NAME);
        assertEquals(1, directives.size(), "directive count mismatch");
        Directive typeDirective = directives.get(0);
        assertEquals("Photo", ((StringValue)typeDirective.getArgument("name").getValue()).getValue(), "type directive name mismatch");

        List<FieldDefinition> photoFields = photoTypeDef.getFieldDefinitions();
        assertNotNull(photoFields, "null photo fields");
        Optional<FieldDefinition> propertiesfield = findFieldDefinition(photoFields, "properties");
        assertTrue(propertiesfield.isPresent(), "properties field not found");
        FieldDefinition propertiesField = propertiesfield.get();
        TypeName type =(TypeName) propertiesField.getType();
        String typeName = type.getName();
        Optional<ObjectTypeDefinition> propertiesType = findObjectTypeDefinition(objectTypeDefinitions, typeName);
        assertTrue(propertiesType.isPresent(), "properties type not found");
        ObjectTypeDefinition propertiesTypeDef = propertiesType.get();
        List<FieldDefinition> propertiesFields = propertiesTypeDef.getFieldDefinitions();

        Optional<FieldDefinition> nameField = findFieldDefinition(propertiesFields, "name");
        assertTrue(nameField.isPresent(), "name field not found");
        Optional<FieldDefinition> numberField = findFieldDefinition(propertiesFields, "number");
        assertTrue(numberField.isPresent(), "number field not found");

        // verify the object type and fields were created for the Titles group in Photo
        Optional<ObjectTypeDefinition> photoTitlesType = findObjectTypeDefinition(objectTypeDefinitions, "PhotoTitles");
        assertTrue(photoTitlesType.isPresent(), "photo titles type not found");
        ObjectTypeDefinition photoTitlesTypeDef = photoTitlesType.get();
        List<FieldDefinition> photoTitlesFields = photoTitlesTypeDef.getFieldDefinitions();
        assertNotNull(photoTitlesFields, "null photo titles fields");
        Optional<FieldDefinition> title1Field = findFieldDefinition(photoTitlesFields, "title1");
        assertTrue(title1Field.isPresent(), "title1 field not found");
        Optional<FieldDefinition> title2Field = findFieldDefinition(photoTitlesFields, "title2");
        assertTrue(title2Field.isPresent(), "title2 field not found");

        List<InputObjectTypeDefinition> inputTypeDefinitions = typeDefinitions.getInputObjectTypeDefinitions();

        // verify the input type was created for Photo
        /*
        Optional<InputObjectTypeDefinition> photoInputType = findInputObjectTypeDefinition(inputTypeDefinitions, "PhotoInput");
        assertTrue(photoInputType.isPresent(), "photo input type not found");
        InputObjectTypeDefinition photoInputTypeDef = photoInputType.get();
        List<InputValueDefinition> photoInputFields = photoInputTypeDef.getInputValueDefinitions();
        assertNotNull(photoInputFields, "null photo input fields");
        Optional<InputValueDefinition> nameInputField = findInputValueDefinition(photoInputFields, "name");
        assertTrue(nameInputField.isPresent(), "name input field not found");
        Optional<InputValueDefinition> numberInputField = findInputValueDefinition(photoInputFields, "number");
        assertTrue(numberInputField.isPresent(), "number input field not found");
        Optional<InputValueDefinition> title1InputField = findInputValueDefinition(photoInputFields, "title1");
        assertTrue(title1InputField.isPresent(), "title1 input field not found");
        Optional<InputValueDefinition> title2InputField = findInputValueDefinition(photoInputFields, "title2");
        assertTrue(title2InputField.isPresent(), "title2 input field not found");

         */
    }

    private Optional<FieldDefinition> findFieldDefinition(List<FieldDefinition> fields, String name) {
        return fields.stream().filter(field -> field.getName().equals(name)).findFirst();
    }

    private Optional<ObjectTypeDefinition> findObjectTypeDefinition(List<ObjectTypeDefinition> fields, String name) {
        return fields.stream().filter(field -> field.getName().equals(name)).findFirst();
    }

    private Optional<InputObjectTypeDefinition> findInputObjectTypeDefinition(List<InputObjectTypeDefinition> fields, String name) {
        return fields.stream().filter(field -> field.getName().equals(name)).findFirst();
    }

    private Optional<InputValueDefinition> findInputValueDefinition(List<InputValueDefinition> inputFields, String name) {
        return inputFields.stream().filter(field -> field.getName().equals(name)).findFirst();
    }

}
