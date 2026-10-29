package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Set;

// A controlled list's values, by the list's value type, in each form they take: as its value
// column stores them, as the admin API shows them, and as canonical text -- what an item's value,
// a facet's JSONB text or an editor's entry is compared by, whatever form it arrived in (2 and 2.0,
// or the same instant at two offsets, are one value). DATE values are ISO dates and DATETIME values
// ISO instants in UTC, which is how the API shows them too.
public final class ControlledListValues {

    public static final Set<PropertyType> SUPPORTED_TYPES = Set.of(
            PropertyType.STRING, PropertyType.INTEGER, PropertyType.DECIMAL, PropertyType.DATE, PropertyType.DATETIME);

    private ControlledListValues() {}

    static String columnType(PropertyType type) {
        return switch (type) {
            case STRING -> "VARCHAR(4000)";
            case INTEGER -> "BIGINT";
            case DECIMAL -> "DOUBLE PRECISION";
            case DATE -> "DATE";
            case DATETIME -> "TIMESTAMPTZ";
            default -> throw unsupported(type);
        };
    }

    // A value as entered (the admin API takes them as text), as the column stores it.
    static Object parse(PropertyType type, String text) {
        try {
            return switch (type) {
                case STRING -> text;
                case INTEGER -> Long.parseLong(text.trim());
                case DECIMAL -> finite(Double.parseDouble(text.trim()));
                case DATE -> LocalDate.parse(text.trim());
                case DATETIME -> OffsetDateTime.parse(text.trim()).withOffsetSameInstant(ZoneOffset.UTC);
                default -> throw unsupported(type);
            };
        } catch (NumberFormatException | DateTimeParseException _) {
            throw new IllegalArgumentException("'" + text + "' isn't a valid " + type + " controlled list value");
        }
    }

    // An item's value for a property using the list, as the column stores it -- null when it can't
    // be one of the list's values at all.
    static Object fromItemValue(PropertyType type, Object value) {
        try {
            return switch (value) {
                case String s when type == PropertyType.STRING -> s;
                case Integer i when type == PropertyType.INTEGER -> i.longValue();
                case Long l when type == PropertyType.INTEGER -> l;
                case Number n when type == PropertyType.DECIMAL -> n.doubleValue();
                case String s when type == PropertyType.DATE || type == PropertyType.DATETIME -> parse(type, s);
                case null, default -> null;
            };
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    // A stored value read back, as the API shows it.
    static Object read(ResultSet rs, String column, PropertyType type) throws SQLException {
        return switch (type) {
            case STRING -> rs.getString(column);
            case INTEGER -> rs.getLong(column);
            case DECIMAL -> rs.getDouble(column);
            case DATE -> rs.getObject(column, LocalDate.class).toString();
            case DATETIME -> rs.getObject(column, OffsetDateTime.class).toInstant().toString();
            default -> throw unsupported(type);
        };
    }

    // Canonical text for a value in any of its forms. Something that isn't a value of this type at
    // all keeps its own text, so it simply matches nothing.
    public static String key(PropertyType type, Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        try {
            return switch (type) {
                case INTEGER -> String.valueOf(Long.parseLong(text));
                case DECIMAL -> new BigDecimal(text).stripTrailingZeros().toPlainString();
                case DATE -> LocalDate.parse(text).toString();
                case DATETIME -> OffsetDateTime.parse(text).toInstant().toString();
                default -> text;
            };
        } catch (NumberFormatException | DateTimeParseException _) {
            return text;
        }
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new NumberFormatException();
        return value;
    }

    private static IllegalArgumentException unsupported(PropertyType type) {
        return new IllegalArgumentException("Controlled lists are not supported for property type: " + type);
    }
}
