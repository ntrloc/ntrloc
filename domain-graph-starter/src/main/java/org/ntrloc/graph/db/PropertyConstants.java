package org.ntrloc.graph.db;

import org.ntrloc.graph.db.schema.PropertyType;

import java.util.Map;

public class PropertyConstants {

    public static final String DELETED_PROPERTY_NAME = "deletedProperties";
    public static final String NAME_PROPERTY = "name";
    public static final String UNIQUE_ID_PROPERTY = "uid";
    public static final String ITEM_TYPE_PROPERTY = "itemType";
    public static final String LINK_TYPE_PROPERTY = "linkType";
    public static final String VERSION_PROPERTY = "version";
    public static final String IS_LATEST_VERSION_PROPERTY = "isLatestVersion";
    public static final String STATUS_PROPERTY = "status";
    public static final String TRANSACTION_ID_PROPERTY = "transactionId";
    public static final String REVISION_TYPE_PROPERTY = "revisionType";
    public static final String DESCRIPTION_PROPERTY = "description";

    /** This property is set on system:nodeProperty edges to identify the name of the property they represent */
    public static final String NODE_PROPERTY_NAME_PROPERTY = "nodePropertyName";

    public static final Map<String, PropertyType> IMPLICIT_PROPERTY_TYPES = Map.of(
            IS_LATEST_VERSION_PROPERTY, PropertyType.BOOLEAN,
            ITEM_TYPE_PROPERTY, PropertyType.STRING,
            STATUS_PROPERTY, PropertyType.STRING,
            TRANSACTION_ID_PROPERTY, PropertyType.STRING,
            UNIQUE_ID_PROPERTY, PropertyType.STRING,
            DESCRIPTION_PROPERTY, PropertyType.STRING,
            VERSION_PROPERTY, PropertyType.INT
    );

    private PropertyConstants() {
        // no-op
    }

}
