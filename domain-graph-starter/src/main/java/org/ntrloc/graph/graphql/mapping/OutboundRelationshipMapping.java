package org.ntrloc.graph.graphql.mapping;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.GraphQLSchemaMapper;

public class OutboundRelationshipMapping extends RelationshipMapping {

    private String sourceGraphName;
    private String sourceGraphQlName;
    private EntityMapping targetMapping;

    public OutboundRelationshipMapping(RelationshipDefinition def, EntityMapping targetMapping, GraphQLSchemaMapper mappingContext) {
        super(def, mappingContext);
        sourceGraphName = def.getSourceLabel();
        sourceGraphQlName = CaseUtils.toCamelCase(sourceGraphName, false, '_', '-');
        this.targetMapping = targetMapping;
    }

    public String getSourceGraphName() {
        return sourceGraphName;
    }

    public String getSourceGraphQlName() {
        return sourceGraphQlName;
    }

    public EntityMapping getTargetMapping() {
        return targetMapping;
    }
}
