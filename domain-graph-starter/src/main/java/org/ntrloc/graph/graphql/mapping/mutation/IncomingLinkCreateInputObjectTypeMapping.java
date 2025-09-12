package org.ntrloc.graph.graphql.mapping.mutation;

import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps an entity's inbound relationship to an instruction to create a new instance of that relationship. */
public class IncomingLinkCreateInputObjectTypeMapping extends IncomingLinkAbstractInputObjectTypeMapping {

    private static final Logger LOG = LoggerFactory.getLogger(IncomingLinkCreateInputObjectTypeMapping.class);

    public IncomingLinkCreateInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        super("%s %s %s Link Create Input", sourceLinkDefinition, propertiesMapping, selectorChoiceInputObjectTypeMapping);
    }

    List<LinkCreateMutation> parseLinkCreateMutations(List<Map<String, Map<String, Object>>> mutationObjects) {
        List<LinkCreateMutation> retList = new ArrayList<>();

        for (var mutationMap : mutationObjects) {
            var targetObject = mutationMap.get(sourceFieldName);
            if (targetObject == null) {
                throw new IllegalArgumentException("Incoming link create must contain a field named " + sourceFieldName);
            } else {
                Selector selector = selectorChoiceInputObjectTypeMapping.parseSelector(targetObject);
                LinkCreateMutation mutation = new LinkCreateMutation();
                mutation.setLinkType(sourceLinkDefinition.getName());
                mutation.setSelector(selector);
                retList.add(mutation);
            }
        }

        return retList;
    }

}
