package org.ntrloc.graph.db.partition.process;

import org.springframework.context.ApplicationContext;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

// Replaces Flowable's own default beans map: SpringProcessEngineConfiguration.initBeans() only
// builds its SpringBeanFactoryProxyMap -- get(key) is a bare beanFactory.getBean((String) key),
// exposing the *entire* ApplicationContext by bean name to every ${...} in a script task or
// delegateExpression -- if config.beans is still null when buildEngine() runs (confirmed against
// that class's source directly). ProcessEngineConfig pre-sets this instance instead, before
// buildEngine() runs, the same "pre-setting sticks because the default is only built if the field
// is still null" pattern already used there for the DataManagers. SpringDmnEngineConfigurator
// auto-shares whatever beans map the process engine ends up with (DmnEngineConfig's own comment),
// so this closes off DMN expression resolution too, with no separate wiring there.
//
// Built once at startup from getBeansWithAnnotation(), not resolved live per lookup -- a process
// script's ${x} either resolves to a bean explicitly marked @ProcessAccessible or it doesn't
// resolve at all, the same "not found" outcome EL already gives for a genuinely nonexistent name.
// A real, unannotated bean therefore fails to resolve exactly like a typo would, rather than with
// some distinct "access denied" error -- deliberately: this is a closed-by-default allowlist, not
// a permission check surfacing which beans exist but are off-limits.
//
// Copied into a real Map<Object, Object> up front (rather than wrapping the Map<String, Object>
// getBeansWithAnnotation() returns) so every Map method below is a plain delegation -- wrapping
// the narrower-keyed map directly would need per-method key/entry conversions to satisfy
// Map<Object, Object>'s generic signature (Set<Object> is not a supertype of Set<String>).
public class ProcessAccessibleBeansMap implements Map<Object, Object> {

    private final Map<Object, Object> beans;

    public ProcessAccessibleBeansMap(ApplicationContext applicationContext) {
        this.beans = Map.copyOf(applicationContext.getBeansWithAnnotation(ProcessAccessible.class));
    }

    @Override
    public Object get(Object key) {
        return beans.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return beans.containsKey(key);
    }

    @Override
    public int size() {
        return beans.size();
    }

    @Override
    public boolean isEmpty() {
        return beans.isEmpty();
    }

    @Override
    public boolean containsValue(Object value) {
        return beans.containsValue(value);
    }

    @Override
    public Set<Object> keySet() {
        return beans.keySet();
    }

    @Override
    public Collection<Object> values() {
        return beans.values();
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        return beans.entrySet();
    }

    @Override
    public Object put(Object key, Object value) {
        throw new UnsupportedOperationException(
                "Process-accessible beans are fixed at startup, from @ProcessAccessible-annotated components");
    }

    @Override
    public void putAll(Map<?, ?> m) {
        throw new UnsupportedOperationException(
                "Process-accessible beans are fixed at startup, from @ProcessAccessible-annotated components");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException(
                "Process-accessible beans are fixed at startup, from @ProcessAccessible-annotated components");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException(
                "Process-accessible beans are fixed at startup, from @ProcessAccessible-annotated components");
    }
}
