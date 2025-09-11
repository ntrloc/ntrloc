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

/** Maps an entity's inbound relationship to an instruction to create a new instance of that relationship. */
public class IncomingLinkCreateInputObjectTypeMapping extends IncomingLinkAbstractInputObjectTypeMapping {

    public IncomingLinkCreateInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        super("%s %s %s Link Create Input", sourceLinkDefinition, propertiesMapping, selectorChoiceInputObjectTypeMapping);
    }

    List<LinkCreateMutation> parseLinkCreateMutations(ArrayValue arrayValue) {
        List<LinkCreateMutation> retList = new ArrayList<>();

        for (var value : arrayValue.getValues()) {
            var objectValue = (ObjectValue) value;
            Optional<ObjectField> sourceOpt = objectValue.getObjectFields().stream().filter(field -> field.getName().equals(sourceFieldName)).findFirst();
            if (sourceOpt.isPresent()) {
                ObjectField sourceField = sourceOpt.get();
                Selector selector = selectorChoiceInputObjectTypeMapping.parseSelector((ObjectValue) sourceField.getValue());
                LinkCreateMutation mutation = new LinkCreateMutation();
                mutation.setLinkType(sourceLinkDefinition.getName());
                mutation.setSelector(selector);
                retList.add(mutation);
            } else {
                throw new IllegalArgumentException("Array value for incoming link create must contain a field named " + sourceFieldName);
            }
        }

        return retList;
    }

}
