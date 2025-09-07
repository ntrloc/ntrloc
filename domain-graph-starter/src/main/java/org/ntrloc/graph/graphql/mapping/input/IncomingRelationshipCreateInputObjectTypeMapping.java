package org.ntrloc.graph.graphql.mapping.input;

import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

/** Maps an entity's inbound relationship to an instruction to create a new instance of that relationship. */
public class IncomingRelationshipCreateInputObjectTypeMapping extends IncomingRelationshipAbstractInputObjectTypeMapping {

    public IncomingRelationshipCreateInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, RelationshipPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Create Input", sourceLinkDefinition, propertiesMapping, matcherChoiceMapping);
    }

}
