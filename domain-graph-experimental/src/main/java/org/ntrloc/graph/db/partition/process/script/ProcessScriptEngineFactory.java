package org.ntrloc.graph.db.partition.process.script;

import org.flowable.common.engine.impl.scripting.FlowableScriptEngine;
import org.flowable.common.engine.impl.scripting.JSR223FlowableScriptEngine;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Builds the FlowableScriptEngine ProcessEngineConfig pre-sets via config.setScriptEngine(...) so
// process scripts (Groovy or JavaScript) can reference org.ntrloc.* classes by simple name --
// see ImportingGroovyScriptEngineFactory (Groovy) and ImportingFlowableScriptEngine
// (JavaScript) for the two, deliberately different, per-language mechanisms this composes.
public final class ProcessScriptEngineFactory {

    private ProcessScriptEngineFactory() {
    }

    public static FlowableScriptEngine build(ProcessScriptProperties properties) {
        List<String> importPackages = properties.importPackages();
        failFastOnCollisions(importPackages);

        JSR223FlowableScriptEngine flowableScriptEngine = new JSR223FlowableScriptEngine();
        // registerEngineName("groovy", ...), not addScriptEngineFactory(...) -- the latter keys
        // registration off ScriptEngineFactory.getEngineName() ("Groovy Scripting Engine" for
        // Groovy's own factory class), not the "groovy" scriptFormat value Flowable actually
        // looks up by. Registering directly under that exact key is what makes
        // getEngineByName("groovy") resolve to this factory instead of falling through to the
        // ServiceLoader-discovered stock one.
        flowableScriptEngine.getScriptEngineManager()
                .registerEngineName("groovy", new ImportingGroovyScriptEngineFactory(importPackages));

        return new ImportingFlowableScriptEngine(flowableScriptEngine, importPackages);
    }

    // Groovy's addStarImports and Nashorn's JavaImporter each resolve a bare name against
    // whichever configured package happens to contain it -- neither engine agrees with the other
    // on a tie-break if two packages both declare the same simple name (confirmed via
    // NativeJavaImporter.createProperty: Nashorn favors the *last*-listed package; Groovy's
    // import-resolution order wasn't even checked, deliberately, because relying on either engine
    // picking "the same one" the other would is exactly the kind of silent guess ntrloc prefers
    // to refuse outright -- see ProcessAccessible's own class comment for the same positive-
    // assertion preference applied elsewhere in this package). Failing at startup, once, forces
    // the collision to be resolved in config instead.
    private static void failFastOnCollisions(List<String> importPackages) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);

        Map<String, String> packageBySimpleName = new HashMap<>();
        for (String importPackage : importPackages) {
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(importPackage)) {
                String className = beanDefinition.getBeanClassName();
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                String existingPackage = packageBySimpleName.putIfAbsent(simpleName, importPackage);
                if (existingPackage != null && !existingPackage.equals(importPackage)) {
                    throw new IllegalStateException(
                            "ntrloc.process.script.import-packages: '" + simpleName + "' exists in both '"
                                    + existingPackage + "' and '" + importPackage
                                    + "' -- unqualified process-script resolution would be ambiguous. "
                                    + "Rename one of the classes, or remove one of the two packages from the list.");
                }
            }
        }
    }
}
