package org.ntrloc.graph.graphql.mapping.input;

import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

/** Maps an entity's incoming relationship to an instruction to create a new instance of that relationship. */
public class IncomingRelationshipUpdateInputObjectTypeMapping extends IncomingRelationshipAbstractInputObjectTypeMapping {

    public IncomingRelationshipUpdateInputObjectTypeMapping(RelationshipDefinition sourceRelationshipDefinition, RelationshipPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Update Input", sourceRelationshipDefinition, propertiesMapping, matcherChoiceMapping);
    }

}
