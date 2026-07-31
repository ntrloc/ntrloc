package org.ntrloc.graph.db.partition.process.script;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.jsr223.GroovyScriptEngineFactory;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;

import javax.script.ScriptEngine;
import java.util.List;

// addStarImports(pkg) is the programmatic equivalent of "import pkg.*" on every script this
// factory's engine compiles -- resolved lazily by the classloader against each configured
// package, same on-demand shape as Nashorn's JavaImporter (ImportingFlowableScriptEngine) but
// fixed here at the classloader/compiler level instead of by rewriting script text, since
// Groovy's `new X(...)` is a compile-time construct the JSR-223 Bindings map has no lever over.
class ImportingGroovyScriptEngineFactory extends GroovyScriptEngineFactory {

    private final GroovyClassLoader classLoader;

    ImportingGroovyScriptEngineFactory(List<String> importPackages) {
        CompilerConfiguration compilerConfiguration = new CompilerConfiguration(CompilerConfiguration.DEFAULT);
        compilerConfiguration.addCompilationCustomizers(
                new ImportCustomizer().addStarImports(importPackages.toArray(new String[0])));
        this.classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), compilerConfiguration);
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new GroovyScriptEngineImpl(classLoader);
    }
}
