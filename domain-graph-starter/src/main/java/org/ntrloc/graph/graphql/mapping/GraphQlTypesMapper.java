package org.ntrloc.graph.graphql.mapping;

import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputTypeMapping;

import java.util.Set;

public class GraphQlTypesMapper {

    /* -----  Global matcher mappings ----- */

    private MatcherChoiceInputTypeMapping matcherChoiceInputTypeMapping = new MatcherChoiceInputTypeMapping();

    void mapTypes(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        EntityInputTypesMapper inputTypesMapper = new EntityInputTypesMapper();
        inputTypesMapper.parseEntityInputTypes(entityDefinitions, relationshipDefinitions, matcherChoiceInputTypeMapping);
    }

}
