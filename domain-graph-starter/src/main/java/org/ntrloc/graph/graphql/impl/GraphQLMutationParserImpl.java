package org.ntrloc.graph.graphql.impl;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Field;
import graphql.language.Value;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.graphql.GraphQLMutationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GraphQLMutationParserImpl implements GraphQLMutationParser {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLMutationParserImpl.class);

    @Override
    public List<EntityMutation> parseMutations(Field mutationField) {
        var ss = mutationField.getSelectionSet();
        ss.getSelections().forEach(selection -> {
            Field childField = (Field) selection;
            List<Argument> arguments = childField.getArguments(); // this is the list of inputs
            for (Argument argument : arguments) {
                ArrayValue inputs = (ArrayValue) argument.getValue();
                for (Value value : inputs.getValues()) {
                    LOG.info("Mutating with {}", value);
                }
            }
        });
        return List.of();
    }

}
