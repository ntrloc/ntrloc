package org.ntrloc.graph.graphql.scalars;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;

@DgsScalar(name = "Date")
public class DateScalar implements Coercing<String, String> {

    private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private static String DATE_ERROR_MESSAGE = "Not a valid date";

    @Override
    public @Nullable String serialize(@NonNull Object dataFetcherResult, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingSerializeException {
        if (dataFetcherResult instanceof String d) {
            try {
                dateFormat.parse(d);
                return d;
            } catch (ParseException pe) {
                throw new CoercingSerializeException(DATE_ERROR_MESSAGE);
            }
        }
        throw new CoercingSerializeException(DATE_ERROR_MESSAGE);
    }

    @Override
    public @Nullable String parseValue(@NonNull Object input, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingParseValueException {
        if (input instanceof String s) {
            try {
                dateFormat.parse(s);
                return s;
            } catch (ParseException pe) {
                throw new CoercingSerializeException(DATE_ERROR_MESSAGE);
            }
        }
        throw new CoercingParseValueException(DATE_ERROR_MESSAGE);
    }

    @Override
    public @Nullable String parseLiteral(@NonNull Value<?> input, @NonNull CoercedVariables variables, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingParseLiteralException {
        if (input instanceof StringValue sv) {
            try {
                dateFormat.parse(sv.getValue());
                return sv.getValue();
            } catch (ParseException pe) {
                throw new CoercingSerializeException(DATE_ERROR_MESSAGE);
            }
        } else {
            throw new CoercingParseLiteralException(DATE_ERROR_MESSAGE);
        }
    }

}
