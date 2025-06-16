package org.nterloc.graph;

import java.lang.annotation.*;
import org.springframework.context.annotation.Conditional;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(PropertyPrefixCondition.class)
public @interface ConditionalOnPropertyPrefix {
    String prefix();
}
