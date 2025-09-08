package org.ntrloc.graph.graphql.mapping.mutation;

import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

/** Maps an entity's incoming relationship to an instruction to create a new instance of that relationship. */
public class IncomingLinkUpdateInputObjectTypeMapping extends IncomingLinkAbstractInputObjectTypeMapping {

    public IncomingLinkUpdateInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Update Input", sourceLinkDefinition, propertiesMapping, matcherChoiceMapping);
    }

}
