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

import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;

@DgsScalar(name = "Date")
public class DateScalar implements Coercing<Date, String> {

    @Override
    public @Nullable String serialize(@NonNull Object dataFetcherResult, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingSerializeException {
        if (dataFetcherResult instanceof Date d) {
            return d.toString();
        }
        throw new CoercingSerializeException("Not a valid date");
    }

    @Override
    public @Nullable Date parseValue(@NonNull Object input, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingParseValueException {
        if (input instanceof String s) {
            return parse(s);
        }
        throw new CoercingParseValueException("Not a valid date");
    }

    @Override
    public @Nullable Date parseLiteral(@NonNull Value<?> input, @NonNull CoercedVariables variables, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingParseLiteralException {
        if (input instanceof StringValue sv) {
            return parse(sv.getValue());
        } else {
            throw new CoercingParseLiteralException("Not a valid date");
        }
    }

    private Date parse(String input) {
        LocalDate date = LocalDate.parse(input);
        return Date.from(date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    }

}
