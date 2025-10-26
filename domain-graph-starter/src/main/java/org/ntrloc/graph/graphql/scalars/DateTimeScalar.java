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
import java.util.Date;
import java.util.Locale;

@DgsScalar(name = "DateTime")
public class DateTimeScalar implements Coercing<Date, String> {

    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

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
            try {
                return parse(s);
            } catch (ParseException e) {
                throw new CoercingParseValueException(e);
            }
        }
        throw new CoercingParseValueException("Not a valid date");
    }

    @Override
    public @Nullable Date parseLiteral(@NonNull Value<?> input, @NonNull CoercedVariables variables, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) throws CoercingParseLiteralException {
        if (input instanceof StringValue sv) {
            try {
                return parse(sv.getValue());
            } catch (ParseException e) {
                throw new CoercingParseValueException(e);
            }
        } else {
            throw new CoercingParseLiteralException("Not a valid date");
        }
    }

    private Date parse(String input) throws ParseException {
        return dateFormat.parse(input);
    }

}
