package org.ntrloc.graph.db;

public class LabelConstants {

    public static final String IS_REVISION_OF_LABEL = "system:is-revision-of";
    public static final String HAS_PREVIOUS_VERSION_LABEL = "system:has-previous-version";

    public static final String ID_CATALOG_LABEL = "system:idCatalog";
    public static final String REVISION_LABEL = "system:revision";
    public static final String DATA_LABEL = "system:data";
    public static final String NODE_PROPERTY_EDGE_LABEL = "system:nodeProperty";

    public static final String ITEM_DEFINITION_LABEL = "system:entityDefinition";
    public static final String LINK_DEFINITION_LABEL = "system:relationshipDefinition";
    public static final String LINK_SOURCE_LABEL = "system:has-source-item";
    public static final String LINK_TARGET_LABEL = "system:has-target-item";
    public static final String PROPERTY_GROUP_DEFINITION_LABEL = "system:propertyGroupDefinition";
    public static final String PROPERTY_DEFINITION_LABEL = "system:propertyDefinition";

    private LabelConstants() {
        // No-op
    }

}
