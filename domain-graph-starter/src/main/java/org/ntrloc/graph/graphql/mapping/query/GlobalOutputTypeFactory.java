package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;

import java.util.List;

public class GlobalOutputTypeFactory {

    public static final String BINARY_OBJECT_TYPE_NAME = "Binary";

    public static ObjectTypeDefinition getBinaryObjectTypeDefinition() {
        return ObjectTypeDefinition.newObjectTypeDefinition()
                .name(BINARY_OBJECT_TYPE_NAME)
                .fieldDefinitions(List.of(
                        FieldDefinition.newFieldDefinition().name("id").type(new TypeName("String")).build(),
                        FieldDefinition.newFieldDefinition().name("md5").type(new TypeName("String")).build(),
                        FieldDefinition.newFieldDefinition().name("sha256").type(new TypeName("String")).build(),
                        FieldDefinition.newFieldDefinition().name("mimeType").type(new TypeName("String")).build(),
                        FieldDefinition.newFieldDefinition().name("url").type(new TypeName("String")).build(),
                        FieldDefinition.newFieldDefinition().name("length").type(new TypeName("Int")).build() // TODO: we need a Long scalar?
                ))
                .build();
    }

}
