package org.ntrloc.graph.graphql.impl;

import graphql.language.ArrayValue;
import graphql.language.Field;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import org.ntrloc.graph.db.language.mutation.EntityCreateMutation;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.db.language.mutation.Property;
import org.ntrloc.graph.db.language.mutation.StringProperty;
import org.ntrloc.graph.graphql.GraphQLMutationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GraphQLMutationParserImpl implements GraphQLMutationParser {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLMutationParserImpl.class);

    @Override
    public List<EntityMutation> parseMutations(Field mutationField) {
        var ss = mutationField.getSelectionSet();

        List<EntityMutation> mutations = ss.getSelections().stream().map(entityTypeSelection -> {
            return parseEntityMutations((Field) entityTypeSelection);
        }).flatMap(List::stream).toList();

        return mutations;
    }

    private List<EntityMutation> parseEntityMutations(Field mutationField) {
        String entityName = mutationField.getName();

        // TODO: it might be better to use the graphQL type mapping objects to parse these things so the field names are guaranteed to match.

        return mutationField.getArguments().stream().map(argument -> {
            EntityMutation mutation = null;
            ArrayValue inputs = (ArrayValue) argument.getValue();
            for (Value value : inputs.getValues()) {
                ObjectValue objectValue = (ObjectValue) value;
                for (ObjectField mutationInstruction: objectValue.getObjectFields()) { // each of these is a separate mutation instruction
                    LOG.info("Mutating with {}", mutationInstruction);
                    if (mutationInstruction.getName().equals("create")) {
                        var createMutation = new EntityCreateMutation();
                        mutation = createMutation;
                        createMutation.setEntityType(entityName);

                        ObjectValue mutationValue = (ObjectValue) mutationInstruction.getValue();
                        Map<String, Value> mutationValueFields = mutationValue.getObjectFields().stream().collect(java.util.stream.Collectors.toMap(ObjectField::getName, ObjectField::getValue));
                        LOG.info("Mutating with {}", mutationValueFields);

                        ObjectValue properties = (ObjectValue) mutationValueFields.get("properties");
                        if (properties != null) {
                            LOG.info("Working with properties {}", properties);
                            List<ObjectField> propertyFields = properties.getObjectFields();
                            List<? extends Property> mappedProperties = propertyFields.stream().map(p -> {
                                String propertyName = p.getName();
                                Value propertyValue = p.getValue();
                                if (propertyValue instanceof StringValue) {
                                    return new StringProperty(propertyName, ((StringValue) propertyValue).getValue());
                                } else {
                                    throw new IllegalArgumentException("Unknown property type " + propertyValue.getClass().getName());
                                }
                            }).toList();
                            Map<String, Property> mappedPropertiesMap = mappedProperties.stream().collect(java.util.stream.Collectors.toMap(Property::getName, p -> p));
                            createMutation.setProperties(mappedPropertiesMap);
                        }

                    } else {
                        throw new IllegalArgumentException("Unknown mutation type " + mutationInstruction.getName());
                    }
                }
            }
            LOG.info("Parsed mutation {}", mutation);
            return mutation;
        }).toList();

    }

}
