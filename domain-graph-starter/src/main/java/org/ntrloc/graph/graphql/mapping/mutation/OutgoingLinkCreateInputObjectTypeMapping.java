package org.ntrloc.graph.graphql.mapping.mutation;

import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingLinkCreateInputObjectTypeMapping extends OutgoingLinkAbstractInputObjectTypeMapping {

    private static final Logger LOG = LoggerFactory.getLogger(OutgoingLinkCreateInputObjectTypeMapping.class);

    private static final String PROPERTIES_FIELD_NAME = "properties";

    public OutgoingLinkCreateInputObjectTypeMapping(LinkDefinition targetLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Create Input", targetLinkDefinition, propertiesMapping, matcherChoiceMapping);
    }

    List<LinkCreateMutation> parseLinkCreateMutations(List<Map<String, Map<String, Object>>> mutationObjects) {
        List<LinkCreateMutation> retList = new ArrayList<>();

        for (var mutationMap : mutationObjects) {
            var targetObject = mutationMap.get(targetFieldName);
            if (targetObject == null) {
                throw new IllegalArgumentException("Outgoing link create must contain a field named " + targetFieldName);
            } else {
                Selector selector = selectorChoiceInputObjectTypeMapping.parseSelector(targetObject);
                LinkCreateMutation mutation = new LinkCreateMutation();
                mutation.setLinkType(targetLinkDefinition.getName());
                mutation.setSelector(selector);

                if (mutationMap.containsKey(PROPERTIES_FIELD_NAME)) {
                    List<? extends Property> properties = propertiesMapping.mapProperties((Map<String, Object>) mutationMap.get(PROPERTIES_FIELD_NAME));
                    mutation.setProperties(properties);
                }

                retList.add(mutation);
            }
        }

        return retList;
    }


}
