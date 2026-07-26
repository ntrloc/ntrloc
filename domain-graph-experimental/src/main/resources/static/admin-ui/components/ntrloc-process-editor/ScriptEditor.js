import { EditorView, basicSetup } from '../../vendor/codemirror/codemirror.js';
import { EditorState, Compartment } from '../../vendor/codemirror/codemirror-state.js';
import { StreamLanguage, LanguageSupport, HighlightStyle, syntaxHighlighting } from '../../vendor/codemirror/codemirror-language.js';
import { javascript } from '../../vendor/codemirror/codemirror-lang-javascript.js';
import { groovy } from '../../vendor/codemirror/codemirror-legacy-modes-mode-groovy.js';
import { tags } from '../../vendor/codemirror/lezer-highlight.js';

// scriptFormat values this editor can highlight -- matches the two JSR-223 engines actually on
// the classpath (nashorn-core, groovy-jsr223; see the domain-graph-experimental pom). Groovy has
// no Lezer grammar of its own, so it goes through the legacy StreamLanguage port instead of the
// javascript() language package's proper incremental parser.
const LANGUAGES = {
  groovy: () => new LanguageSupport(StreamLanguage.define(groovy)),
  javascript: () => javascript(),
};

// Re-themed to fit the app's own dark palette (ntrloc-process-editor.js's --bg/--text/--border)
// rather than pulling in one of CodeMirror's bundled themes -- same reasoning as the diagram-js
// chrome and Material buttons being re-themed elsewhere in this component, rather than shipping
// two inconsistent visual languages side by side.
const editorTheme = EditorView.theme({
  '&': {
    color: 'var(--text)',
    backgroundColor: 'var(--bg)',
    border: '1px solid var(--border)',
    borderRadius: '4px',
    fontSize: '12px',
    height: '320px',
  },
  '.cm-scroller': { overflow: 'auto', fontFamily: 'monospace' },
  '.cm-content': { caretColor: 'var(--text)' },
  '.cm-gutters': {
    backgroundColor: 'var(--panel-bg)',
    color: 'var(--muted)',
    border: 'none',
  },
  '.cm-activeLine': { backgroundColor: 'rgba(255, 255, 255, 0.05)' },
  '.cm-activeLineGutter': { backgroundColor: 'rgba(255, 255, 255, 0.05)' },
  '&.cm-focused': { outline: '1px solid var(--accent)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': {
    backgroundColor: 'rgba(74, 158, 255, 0.25) !important',
  },
}, { dark: true });

// Colors lifted from a standard dark-theme convention (keywords violet, strings copper, comments
// green, ...) so Groovy and JavaScript read the same way any other dark-editor theme would --
// deliberately not derived from the app's own accent palette, which doesn't have enough distinct
// hues for this many token categories.
const highlightStyle = HighlightStyle.define([
  { tag: [tags.keyword, tags.controlKeyword, tags.moduleKeyword], color: '#c586c0' },
  { tag: [tags.string, tags.special(tags.string)], color: '#ce9178' },
  { tag: tags.comment, color: '#6a9955', fontStyle: 'italic' },
  { tag: [tags.number, tags.bool, tags.null], color: '#b5cea8' },
  { tag: [tags.function(tags.variableName), tags.function(tags.propertyName)], color: '#dcdcaa' },
  { tag: tags.variableName, color: 'var(--text)' },
  { tag: tags.propertyName, color: '#9cdcfe' },
  { tag: tags.definition(tags.variableName), color: '#4fc1ff' },
  { tag: tags.typeName, color: '#4ec9b0' },
  { tag: tags.operator, color: 'var(--text)' },
  { tag: tags.bracket, color: 'var(--muted)' },
]);

// Wraps a CodeMirror EditorView bound to a single Script Task's script body. One instance is
// created per panel render whenever a Script Task is selected (ntrloc-process-editor.js's
// renderPanel) and destroyed as soon as selection moves elsewhere -- CodeMirror's EditorView
// keeps its own DOM/listeners alive independent of the panel's innerHTML being replaced, so
// dropping the reference without calling destroy() would leak both. The language Compartment lets
// the Script Format <select> swap the grammar live via setLanguage() instead of tearing down and
// rebuilding the whole view on every format change.
export class ScriptEditor {
  constructor(container, { doc, language, onChange }) {
    this._languageCompartment = new Compartment();

    this.view = new EditorView({
      parent: container,
      // CodeMirror's own root-detection (used to decide where to inject its theme/highlight
      // stylesheet) walks up via node.assignedSlot before node.parentNode -- fine normally, but
      // once that walk reaches an actually-slotted ancestor (e.g. ntrloc-state-machine-editor.js's
      // <div slot="content">, projected into <md-dialog>'s shadow root), assignedSlot redirects it
      // into that shadow tree instead of continuing up the light DOM, landing the stylesheet in
      // the dialog's shadow root's adoptedStyleSheets instead of the document -- which is where
      // this container's OWN (unshadowed) styling actually needs it. Found live: a Script Task's
      // editor rendered as bare unstyled text (just the gutter's "1" and the raw content, no
      // .cm-editor box/gutter/syntax-color styling) the first time one was ever constructed while
      // nested this way; forcing `root: document` here bypasses that walk entirely.
      root: document,
      state: EditorState.create({
        doc,
        extensions: [
          basicSetup,
          editorTheme,
          syntaxHighlighting(highlightStyle),
          this._languageCompartment.of(this._languageExtension(language)),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) onChange(update.state.doc.toString());
          }),
        ],
      }),
    });
  }

  _languageExtension(language) {
    const factory = LANGUAGES[language];
    return factory ? factory() : [];
  }

  setLanguage(language) {
    this.view.dispatch({
      effects: this._languageCompartment.reconfigure(this._languageExtension(language)),
    });
  }

  destroy() {
    this.view.destroy();
  }
}
