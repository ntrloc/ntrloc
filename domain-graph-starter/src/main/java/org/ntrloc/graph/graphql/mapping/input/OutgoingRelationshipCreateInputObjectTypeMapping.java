package org.ntrloc.graph.graphql.mapping.input;

import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingRelationshipCreateInputObjectTypeMapping extends OutgoingRelationshipAbstractInputObjectTypeMapping  {

    public OutgoingRelationshipCreateInputObjectTypeMapping(RelationshipDefinition targetRelationshipDefinition, RelationshipPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Create Input", targetRelationshipDefinition, propertiesMapping, matcherChoiceMapping);
    }


}
