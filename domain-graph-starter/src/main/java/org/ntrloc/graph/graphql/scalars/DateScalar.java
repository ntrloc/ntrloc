package org.ntrloc.graph.graphql.scalars;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;

import java.time.LocalDate;
import java.util.Date;

@DgsScalar(name = "Date")
public class DateScalar implements Coercing<Date, String> {

    @Override
    public String serialize(Object dataFetcherResult) {
        if (dataFetcherResult instanceof Date d) {
            return d.toString();
        }
        throw new CoercingSerializeException("Not a valid date");
    }

    @Override
    public Date parseValue(Object input) {
        if (input instanceof String s) {
            return parse(s);
        }
        throw new CoercingParseValueException("Not a valid date");
    }

    @Override
    public Date parseLiteral(Object input) {
        if (input instanceof StringValue sv) {
            return parse(sv.getValue());
        } else if (input instanceof String s) {
            return parse(s);
        }
        throw new CoercingParseLiteralException("Not a valid date");
    }

    private Date parse(String input) {
        LocalDate date = LocalDate.parse(input);
        return Date.from(date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    }

}
