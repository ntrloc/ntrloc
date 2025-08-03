package org.ntrloc.graph.db.projector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class ProjectionSpec {

    protected Map<String, Object> getSimplifiedProperties(Map<String, Object> properties) {
        if (properties == null) {
            return null;
        } else {
            Map<String, Object> retMap = new HashMap<>();
            for (var entry : properties.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (entry.getValue() instanceof List) {
                    List<Object> list = (List<Object>) entry.getValue();
                    if (list.size() == 1) {
                        retMap.put(key, list.get(0));
                    } else {
                        retMap.put(key, list);
                    }
                } else {
                    retMap.put(key, value);
                }
            }
            return retMap;
        }
    }

}
