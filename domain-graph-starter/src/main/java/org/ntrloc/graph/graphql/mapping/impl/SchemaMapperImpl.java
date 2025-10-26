package org.ntrloc.graph.graphql.mapping.impl;

import graphql.language.Field;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.ScalarTypeDefinition;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.ScalarTypeProducer;
import org.ntrloc.graph.graphql.mapping.SchemaMapper;
import org.ntrloc.graph.graphql.mapping.mutation.MutationObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.query.QueryObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.scalars.BinaryScalarTypeMapping;
import org.ntrloc.graph.graphql.mapping.scalars.DateScalarTypeMapping;
import org.ntrloc.graph.graphql.mapping.scalars.DateTimeScalarTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SchemaMapperImpl implements SchemaMapper {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMapperImpl.class);

    private List<InputObjectTypeDefinition> inputTypeDefinitions;
    private List<ObjectTypeDefinition> outputTypeDefinitions;
    private List<ObjectTypeExtensionDefinition> extensionDefinitions;

    private MutationObjectTypeMapping mutationObjectTypeMapping;
    private QueryObjectTypeMapping queryObjectTypeMapping;

    private List<ScalarTypeProducer> scalarTypeProducers = List.of(new DateScalarTypeMapping(), new DateTimeScalarTypeMapping(), new BinaryScalarTypeMapping());

    public SchemaMapperImpl() {
    }

    /* --------------------------- GraphQL type mappings --------------------- */

    public void mapSchema(Set<ItemDefinition> itemDefinitions, Set<LinkDefinition> linkDefinitions) {

        queryObjectTypeMapping = new QueryObjectTypeMapping(itemDefinitions, linkDefinitions);
        mutationObjectTypeMapping = new MutationObjectTypeMapping(itemDefinitions, linkDefinitions);

        inputTypeDefinitions = mutationObjectTypeMapping.getInputObjectTypeDefinitions().stream()
                .collect(Collectors.toMap(InputObjectTypeDefinition::getName, inputObjectTypeDefinition -> inputObjectTypeDefinition, (existingValue, newValue) -> existingValue))
                .values().stream().toList();

        List<ObjectTypeDefinition> objectTypeDefinitions = new ArrayList<>();
        objectTypeDefinitions.addAll(mutationObjectTypeMapping.getObjectTypeDefinitions());
        objectTypeDefinitions.addAll(queryObjectTypeMapping.getObjectTypeDefinitions());

        outputTypeDefinitions = objectTypeDefinitions.stream()
                .collect(Collectors.toMap(ObjectTypeDefinition::getName, def -> def, (existingValue, newValue) -> existingValue))
                        .values().stream().toList();

        extensionDefinitions = new ArrayList<>();
    }

    @Override
    public List<ScalarTypeDefinition> getScalarTypes() {
        return scalarTypeProducers.stream().map(ScalarTypeProducer::getScalarTypeDefinition).toList();
    }

    public List<InputObjectTypeDefinition> getInputTypes() {
        return inputTypeDefinitions;
    }

    public List<ObjectTypeDefinition> getOutputTypes() {
        return outputTypeDefinitions;
    }

    public List<ObjectTypeExtensionDefinition> getExtensionTypes() {
        return extensionDefinitions;
    }

    /* -------------------------- GraphQL request parsers -------------------- */

    public Map<String, List<ItemMutation>> parseEntityMutations(Map<String, Object> mutationFields) {
        return mutationObjectTypeMapping.parseItemMutations(mutationFields);
    }

    @Override
    public SelectableItemProjectionSpec parseProjection(Field queryField) {
        LOG.info("Parsing query field {}", queryField);
        return queryObjectTypeMapping.parseQueryField(queryField);
    }

}
