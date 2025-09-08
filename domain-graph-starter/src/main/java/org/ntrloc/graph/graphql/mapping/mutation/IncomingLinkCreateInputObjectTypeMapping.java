package org.ntrloc.graph.graphql.mapping.mutation;

import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

/** Maps an entity's inbound relationship to an instruction to create a new instance of that relationship. */
public class IncomingLinkCreateInputObjectTypeMapping extends IncomingLinkAbstractInputObjectTypeMapping {

    public IncomingLinkCreateInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Create Input", sourceLinkDefinition, propertiesMapping, matcherChoiceMapping);
    }

}
