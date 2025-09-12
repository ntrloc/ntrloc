package org.ntrloc.graph.db;

public class PropertyNameTranslator {

    private PropertyNameTranslator() {
        // no-op
    }

    public static String externalPropertyNameToInternalName(String label, String externalPropertyName) {
        return String.format("%s_%s", label, externalPropertyName);
    }

}
