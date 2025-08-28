package org.ntrloc.graph.graphql.mapping;

import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;

import java.util.Set;

public class SchemaMapper {

    private EntityInputTypesMapper inputTypesMapper;
    private EntityOutputTypesMapper outputTypesMapper;

    public SchemaMapper(EntityInputTypesMapper inputTypesMapper, EntityOutputTypesMapper outputTypesMapper) {
        this.inputTypesMapper = inputTypesMapper;
        this.outputTypesMapper = outputTypesMapper;
    }

    public void mapSchema(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        inputTypesMapper.parseEntityInputTypes(entityDefinitions, relationshipDefinitions);
    }

}
