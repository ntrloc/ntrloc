package org.ntrloc.graph.db.partition.process.script;

import org.flowable.common.engine.api.variable.VariableContainer;
import org.flowable.common.engine.impl.scripting.FlowableScriptEngine;
import org.flowable.common.engine.impl.scripting.FlowableScriptEvaluationRequest;
import org.flowable.common.engine.impl.scripting.FlowableScriptException;
import org.flowable.common.engine.impl.scripting.Resolver;
import org.flowable.common.engine.impl.scripting.ScriptEvaluation;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

// Delegates every call straight to a real JSR223FlowableScriptEngine, except for one thing:
// for a JavaScript-family script, the source text is wrapped in
// "with (new JavaImporter(Packages.pkg1, Packages.pkg2)) { <script> }" before it reaches
// ScriptEngine.eval(). Nashorn's JavaImporter resolves an unqualified name against each argument
// package lazily (NativeJavaImporter.createProperty: pkgName + "." + name, then
// Context.findClass(...)), the same on-demand resolution Groovy's ImportCustomizer gives compiled
// scripts -- see ProcessScriptEngineFactory for the Groovy side, which is fixed at the
// ScriptEngineFactory/classloader level instead, since Groovy's `new X()` is a compile-time
// construct `with` has no equivalent lever over.
// `with` only affects lookups that don't already resolve to something in scope, so execution/
// entityManager/etc. (real script bindings) and Packages/Java (Nashorn's own globals, never
// matched by JavaImporter's own property list) fall through completely unaffected.
class ImportingFlowableScriptEngine implements FlowableScriptEngine {

    private static final Set<String> JAVASCRIPT_LANGUAGES =
            Set.of("javascript", "js", "nashorn", "ecmascript");

    private final FlowableScriptEngine delegate;
    private final String javaImporterExpression;

    ImportingFlowableScriptEngine(FlowableScriptEngine delegate, List<String> importPackages) {
        this.delegate = delegate;
        this.javaImporterExpression = importPackages.isEmpty() ? null : importPackages.stream()
                .map(pkg -> "Packages." + pkg)
                .collect(Collectors.joining(", ", "new JavaImporter(", ")"));
    }

    @Override
    public FlowableScriptEvaluationRequest createEvaluationRequest() {
        return new ImportingEvaluationRequest(delegate.createEvaluationRequest());
    }

    private class ImportingEvaluationRequest implements FlowableScriptEvaluationRequest {

        private final FlowableScriptEvaluationRequest delegateRequest;
        private String language;

        ImportingEvaluationRequest(FlowableScriptEvaluationRequest delegateRequest) {
            this.delegateRequest = delegateRequest;
        }

        @Override
        public FlowableScriptEvaluationRequest language(String language) {
            // ScriptingEngines.evaluate() always calls .language(...) before .script(...) on the
            // same request -- relied on here so the wrapping decision below has the language
            // already available.
            this.language = language;
            delegateRequest.language(language);
            return this;
        }

        @Override
        public FlowableScriptEvaluationRequest script(String script) {
            boolean shouldWrap = javaImporterExpression != null && isJavaScriptFamily(language);
            delegateRequest.script(shouldWrap ? wrap(script) : script);
            return this;
        }

        private String wrap(String script) {
            return "with (" + javaImporterExpression + ") {\n" + script + "\n}";
        }

        private boolean isJavaScriptFamily(String language) {
            return language != null && JAVASCRIPT_LANGUAGES.contains(language.toLowerCase(Locale.ROOT));
        }

        @Override
        public FlowableScriptEvaluationRequest resolver(Resolver resolver) {
            delegateRequest.resolver(resolver);
            return this;
        }

        @Override
        public FlowableScriptEvaluationRequest scopeContainer(VariableContainer scopeContainer) {
            delegateRequest.scopeContainer(scopeContainer);
            return this;
        }

        @Override
        public FlowableScriptEvaluationRequest inputVariableContainer(VariableContainer inputVariableContainer) {
            delegateRequest.inputVariableContainer(inputVariableContainer);
            return this;
        }

        @Override
        public FlowableScriptEvaluationRequest storeScriptVariables() {
            delegateRequest.storeScriptVariables();
            return this;
        }

        @Override
        public ScriptEvaluation evaluate() throws FlowableScriptException {
            return delegateRequest.evaluate();
        }
    }
}
