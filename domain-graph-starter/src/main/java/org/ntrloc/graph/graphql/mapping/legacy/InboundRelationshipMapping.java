package org.ntrloc.graph.graphql.mapping.legacy;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.GraphQLSchemaMapper;

public class InboundRelationshipMapping extends RelationshipMapping {
    private String targetGraphName;
    private String targeteGraphQlName;
    private EntityMapping sourceMapping;

    public InboundRelationshipMapping(RelationshipDefinition def, EntityMapping sourceMapping, GraphQLSchemaMapper mappingContext) {
        super(def, mappingContext);
        targetGraphName = def.getTargetLabel();
        targeteGraphQlName = CaseUtils.toCamelCase(targetGraphName, false, '_', '-');
        this.sourceMapping = sourceMapping;
    }

    public String getTargetGraphQlName() {
        return targeteGraphQlName;
    }

    public String getTargetGraphName() {
        return targetGraphName;
    }

    public EntityMapping getSourceMapping() {
        return sourceMapping;
    }

}
