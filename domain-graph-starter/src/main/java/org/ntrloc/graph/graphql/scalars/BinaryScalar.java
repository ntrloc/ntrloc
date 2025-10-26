package org.ntrloc.graph.graphql.scalars;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.ntrloc.graph.graphql.mapping.scalars.BinaryScalarTypeMapping;

import java.util.Locale;

@DgsScalar(name = BinaryScalarTypeMapping.BINARY_SCALAR_TYPE_NAME)
public class BinaryScalar implements Coercing<String, String> {

    @Override
    public @Nullable String serialize(@NonNull Object dataFetcherResult, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingSerializeException {
        if (dataFetcherResult instanceof String s) {
            return s;
        }
        throw new CoercingSerializeException("Not a valid binary");
    }

    @Override
    public @Nullable String parseValue(@NonNull Object input, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingParseValueException {
        if (input instanceof String s) {
            return s;
        }
        throw new CoercingParseValueException("Not a valid binary");
    }

    @Override
    public @Nullable String parseLiteral(@NonNull Value<?> input, @NonNull CoercedVariables variables, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingParseLiteralException {
        if (input instanceof graphql.language.StringValue sv) {
            return sv.getValue();
        } else {
            throw new CoercingParseLiteralException("Not a valid binary");
        }
    }

}
