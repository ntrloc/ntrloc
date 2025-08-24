package org.ntrloc.graph.graphql;

import graphql.language.Field;
import org.ntrloc.graph.db.mutation.EntityMutation;

import java.util.List;

public interface GraphQLMutationParser {

    List<EntityMutation> parseMutations(Field mutationField);

}
