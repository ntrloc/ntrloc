package org.ntrloc.graph.graphql.mapping.mutation;

import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingLinkUpdateInputObjectTypeMapping extends OutgoingLinkAbstractInputObjectTypeMapping {

    public OutgoingLinkUpdateInputObjectTypeMapping(LinkDefinition targetLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Update Input", targetLinkDefinition, propertiesMapping, matcherChoiceMapping);
    }

}
