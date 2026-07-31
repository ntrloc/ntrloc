package org.ntrloc.graph.db.partition.process;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// A Spring bean's mere existence never implies it's meant to be called from a process script
// (${x} in a Groovy/JS scriptTask) or delegate expression (flowable:delegateExpression="${x}")
// -- positive-assertion convention: reachability from process authoring is something a
// component opts into explicitly, not something it gets by default just by being a bean.
// ProcessAccessibleBeansMap (this package) only ever resolves beans carrying this annotation;
// see ProcessEngineConfig for how that closes off Flowable's own default (which exposes the
// entire ApplicationContext, unrestricted, by bean name -- verified against
// SpringBeanFactoryProxyMap/SpringProcessEngineConfiguration.initBeans()).
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProcessAccessible {
}
