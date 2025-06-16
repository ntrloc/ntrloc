package org.nterloc.graph;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

public class PropertyPrefixCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String prefix = (String) metadata.getAnnotationAttributes(ConditionalOnPropertyPrefix.class.getName()).get("prefix");

        if (StringUtils.isEmpty(prefix)) {
            throw new IllegalArgumentException("Prefix must be specified in @ConditionalOnPropertyPrefix");
        }

        for (PropertySource<?> source : ((AbstractEnvironment) context.getEnvironment()).getPropertySources()) {
            if (source instanceof MapPropertySource) {
                for (String propertyName : ((MapPropertySource) source).getSource().keySet()) {
                    if (propertyName.startsWith(prefix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
