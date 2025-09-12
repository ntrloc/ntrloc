package org.ntrloc.graph.graphql.scalars;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;

@DgsScalar(name = "Binary")
public class BinaryScalar implements Coercing<String, String> {

    @Override
    public String serialize(Object dataFetcherResult) {
        if (dataFetcherResult instanceof String) {
            // Implement email validation and return the serialized string
            return (String) dataFetcherResult;
        }
        throw new CoercingSerializeException("Not a valid binary");
    }

    @Override
    public String parseValue(Object input) {
        if (input instanceof String s) {
            return s;
        }
        throw new CoercingParseValueException("Not a valid binary");
    }

    @Override
    public String parseLiteral(Object input) {
        if (input instanceof StringValue sv) {
            return sv.getValue();
        } else if (input instanceof String s) {
            return s;
        }
        throw new CoercingParseLiteralException("Not a valid binary");
    }

}
