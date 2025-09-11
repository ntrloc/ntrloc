package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.ArrayValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingLinkCreateInputObjectTypeMapping extends OutgoingLinkAbstractInputObjectTypeMapping {

    public OutgoingLinkCreateInputObjectTypeMapping(LinkDefinition targetLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Create Input", targetLinkDefinition, propertiesMapping, matcherChoiceMapping);
    }

    List<LinkCreateMutation> parseLinkCreateMutations(ArrayValue arrayValue) {
        List<LinkCreateMutation> retList = new ArrayList<>();

        for (var value : arrayValue.getValues()) {
            var objectValue = (ObjectValue) value;
            Optional<ObjectField> targetOpt = objectValue.getObjectFields().stream().filter(field -> field.getName().equals(targetFieldName)).findFirst();
            if (targetOpt.isPresent()) {
                ObjectField targetField = targetOpt.get();
                Selector selector = selectorChoiceInputObjectTypeMapping.parseSelector((ObjectValue) targetField.getValue());
                LinkCreateMutation mutation = new LinkCreateMutation();
                mutation.setLinkType(targetLinkDefinition.getName());
                mutation.setSelector(selector);
                retList.add(mutation);
            } else {
                throw new IllegalArgumentException("Array value for outgoing link create must contain a field named " + targetFieldName);
            }
        }

        return retList;
    }


}
