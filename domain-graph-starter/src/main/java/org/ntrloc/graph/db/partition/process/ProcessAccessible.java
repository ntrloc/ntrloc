package org.ntrloc.graph.db.partition.process;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marks a bean as callable from process scripts/delegate expressions (${x}). Being a Spring bean
// isn't enough on its own -- ProcessAccessibleBeansMap only resolves beans carrying this
// annotation, closing off Flowable's default of exposing the whole ApplicationContext.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProcessAccessible {
}
