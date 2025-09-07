package org.ntrloc.graph.graphql.mapping.input;

import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

/** Maps an entity's inbound relationship to an instruction to create a new instance of that relationship. */
public class IncomingRelationshipCreateInputObjectTypeMapping extends IncomingRelationshipAbstractInputObjectTypeMapping {

    public IncomingRelationshipCreateInputObjectTypeMapping(RelationshipDefinition sourceRelationshipDefinition, RelationshipPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Create Input", sourceRelationshipDefinition, propertiesMapping, matcherChoiceMapping);
    }

}
