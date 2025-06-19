package org.ntrloc.graph.db;

public class PropertyNameTranslator {

    public static String externalPropertyNameToInternalName(String label, String externalPropertyName) {
        return String.format("%s_%s", label, externalPropertyName);
    }

}
