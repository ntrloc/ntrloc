injectStyles('ntrloc-predicate-builder-styles', `
  ntrloc-predicate-builder {
    display: contents;
  }
  .predicate-root {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .predicate-pill {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 4px 6px 4px 10px;
    font-size: 13px;
  }
  .predicate-pill.group-pill,
  .predicate-pill.not-pill {
    flex-direction: column;
    align-items: flex-start;
    border-radius: 10px;
    padding: 8px 10px;
    gap: 6px;
  }
  .predicate-type-badge {
    font-size: 10px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    text-transform: uppercase;
    white-space: nowrap;
  }
  .predicate-group-header {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .predicate-combinator-select {
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--accent);
    background: none;
    border: none;
    text-transform: uppercase;
    cursor: pointer;
  }
  /* Left rail rather than another pill outline -- makes a group's children read as "inside"
     the group at a glance, without nesting yet another full pill border around the whole list. */
  .predicate-children {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    padding-left: 10px;
    border-left: 2px solid var(--border);
  }
  .predicate-pill select,
  .predicate-pill input {
    background: transparent;
    border: none;
    border-bottom: 1px solid var(--border);
    color: var(--text);
    font: inherit;
    font-size: 12px;
    padding: 2px 0;
    outline: none;
  }
  .predicate-pill select:focus,
  .predicate-pill input:focus {
    border-bottom-color: var(--accent);
  }
  .predicate-value-input {
    width: 8ch;
  }
  .predicate-pill-actions {
    display: flex;
    align-items: center;
    gap: 2px;
    margin-left: 4px;
  }
  .predicate-pill-actions button {
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 13px;
    padding: 0 4px;
    line-height: 1.6;
  }
  .predicate-pill-actions button:hover {
    color: var(--text);
  }
  .predicate-add-row {
    display: flex;
    gap: 4px;
    align-items: center;
  }
  .predicate-add-row md-text-button {
    --md-text-button-container-height: 26px;
    font-size: 12px;
  }
  .predicate-combine-row {
    display: flex;
    gap: 6px;
    align-items: center;
    font-size: 12px;
    color: var(--muted);
  }
  .predicate-combine-row md-text-button {
    --md-text-button-container-height: 26px;
  }
`);

const PREDICATE_OPERATOR_LABELS = {
  EXISTS: 'exists',
  EQUALS: '=',
  NOT_EQUALS: '≠',
  LESS_THAN: '<',
  LESS_THAN_OR_EQUAL: '≤',
  GREATER_THAN: '>',
  GREATER_THAN_OR_EQUAL: '≥',
  LIKE: 'matches',
};

// Pure validity check, exported as a plain global function (matching this app's no-module-system
// convention) so hosts embedding this component -- ntrloc-states-editor.js today, a future search
// filter pane -- can gate their own Save affordance on it, the same way hasInvalidPendingLinks
// gates schema-editor Save. Independent of any specific host's dirty-tracking scheme.
function predicateHasErrors(predicate) {
  if (predicate == null) return false;
  switch (predicate.type) {
    case 'AND':
    case 'OR':
      return !predicate.predicates || predicate.predicates.length === 0 || predicate.predicates.some(predicateHasErrors);
    case 'NOT':
      return predicate.predicate == null || predicateHasErrors(predicate.predicate);
    case 'PROPERTY_EXISTS':
      return !predicate.propertyName || predicate.propertyName.trim() === '';
    case 'PROPERTY_VALUE':
      return !predicate.propertyName || predicate.propertyName.trim() === ''
        || predicate.value == null || predicate.value === '';
    default:
      return true;
  }
}

// Builder for org.ntrloc.graph.db.projection.Predicate trees (AND/OR/NOT/PROPERTY_EXISTS/
// PROPERTY_VALUE -- see Predicate.java's @JsonSubTypes) -- the same predicate language the
// /api/entity/projection endpoint already executes. Built decoupled from schemaViewModel/any other
// global store on purpose: it's driven purely by `.data = { predicate, properties, onChange }` and
// mutates/replaces its own `predicate` value, calling `onChange(newPredicateOrNull)` on every edit
// instead of reaching for a global notify function, so the exact same element can be dropped into
// the guard-condition editor (backed by a TransitionViewModel field) and, later, a search filter
// pane (backed by whatever local state that pane uses) without modification.
//
// Renders as nested "pills" per the visual direction given for this component: each predicate node
// is a persistently-visible chip labeled with its type, with its own fields editable inline inside
// the chip (not a separate view/edit mode toggle) -- AND/OR/NOT pills contain their children as
// nested pills rather than a generic form layout.
class NtrlocPredicateBuilder extends HTMLElement {
  set data({ predicate, properties, onChange }) {
    this._predicate = predicate ?? null;
    this._properties = properties || [];
    this._onChange = onChange || (() => {});
    this.render();
  }

  get data() {
    return this._predicate;
  }

  connectedCallback() {
    this.render();
  }

  _notify() {
    this._onChange(this._predicate);
    this.render();
  }

  _defaultLeaf() {
    return { type: 'PROPERTY_EXISTS', propertyName: this._properties[0]?.name ?? '' };
  }

  render() {
    this.innerHTML = '';
    const root = document.createElement('div');
    root.className = 'predicate-root';

    if (this._predicate == null) {
      const addButton = document.createElement('md-outlined-button');
      addButton.textContent = '+ Add Condition';
      addButton.addEventListener('click', () => {
        this._predicate = this._defaultLeaf();
        this._notify();
      });
      root.appendChild(addButton);
    } else {
      root.appendChild(this._renderNode(this._predicate, (newNode) => {
        this._predicate = newNode;
        this._notify();
      }));

      const combineRow = document.createElement('div');
      combineRow.className = 'predicate-combine-row';
      const label = document.createElement('span');
      label.textContent = 'Combine with:';
      combineRow.appendChild(label);
      combineRow.appendChild(this._textButton('+ AND', () => {
        this._predicate = { type: 'AND', predicates: [this._predicate, this._defaultLeaf()] };
        this._notify();
      }));
      combineRow.appendChild(this._textButton('+ OR', () => {
        this._predicate = { type: 'OR', predicates: [this._predicate, this._defaultLeaf()] };
        this._notify();
      }));
      combineRow.appendChild(this._textButton('Clear', () => {
        this._predicate = null;
        this._notify();
      }));
      root.appendChild(combineRow);
    }

    this.appendChild(root);
  }

  _textButton(label, onClick) {
    const button = document.createElement('md-text-button');
    button.textContent = label;
    button.addEventListener('click', onClick);
    return button;
  }

  // replaceSelf(newNodeOrNull) lets a node swap itself out in its parent's eyes -- null means
  // "delete me", anything else means "I've become a different node" (e.g. wrapped in NOT). The
  // caller (root render, or a group's per-child closure below) is what actually knows how to
  // splice/replace in its own parent structure.
  _renderNode(node, replaceSelf) {
    switch (node.type) {
      case 'AND':
      case 'OR':
        return this._renderGroup(node, replaceSelf);
      case 'NOT':
        return this._renderNot(node, replaceSelf);
      default:
        return this._renderLeaf(node, replaceSelf);
    }
  }

  _wrapInNotButton(node, replaceSelf) {
    return this._iconButton('¬', 'Wrap in NOT', () => {
      replaceSelf({ type: 'NOT', predicate: node });
    });
  }

  _removeButton(onClick, title = 'Remove') {
    return this._iconButton('✕', title, onClick);
  }

  _iconButton(label, title, onClick) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.title = title;
    button.addEventListener('click', onClick);
    return button;
  }

  _renderLeaf(node, replaceSelf) {
    const pill = document.createElement('div');
    pill.className = 'predicate-pill leaf-pill';

    const propertySelect = document.createElement('select');
    propertySelect.className = 'predicate-property-select';
    for (const prop of this._properties) {
      const option = document.createElement('option');
      option.value = prop.name;
      option.textContent = prop.name;
      option.selected = prop.name === node.propertyName;
      propertySelect.appendChild(option);
    }
    if (!this._properties.some((p) => p.name === node.propertyName)) {
      const option = document.createElement('option');
      option.value = node.propertyName ?? '';
      option.textContent = node.propertyName || '(select property)';
      option.selected = true;
      propertySelect.prepend(option);
    }
    propertySelect.addEventListener('change', (event) => {
      node.propertyName = event.target.value;
      replaceSelf(node);
    });
    pill.appendChild(propertySelect);

    const modeSelect = document.createElement('select');
    modeSelect.className = 'predicate-mode-select';
    const currentMode = node.type === 'PROPERTY_EXISTS' ? 'EXISTS' : node.operator;
    for (const [mode, mlabel] of Object.entries(PREDICATE_OPERATOR_LABELS)) {
      const option = document.createElement('option');
      option.value = mode;
      option.textContent = mlabel;
      option.selected = mode === currentMode;
      modeSelect.appendChild(option);
    }
    modeSelect.addEventListener('change', (event) => {
      const mode = event.target.value;
      if (mode === 'EXISTS') {
        replaceSelf({ type: 'PROPERTY_EXISTS', propertyName: node.propertyName });
      } else {
        replaceSelf({ type: 'PROPERTY_VALUE', propertyName: node.propertyName, operator: mode, value: node.value ?? '' });
      }
    });
    pill.appendChild(modeSelect);

    if (node.type === 'PROPERTY_VALUE') {
      const valueInput = document.createElement('input');
      valueInput.className = 'predicate-value-input';
      valueInput.value = node.value ?? '';
      valueInput.placeholder = 'value';
      valueInput.addEventListener('change', (event) => {
        node.value = event.target.value;
        replaceSelf(node);
      });
      pill.appendChild(valueInput);
    }

    const actions = document.createElement('div');
    actions.className = 'predicate-pill-actions';
    actions.appendChild(this._wrapInNotButton(node, replaceSelf));
    actions.appendChild(this._removeButton(() => replaceSelf(null)));
    pill.appendChild(actions);

    return pill;
  }

  _renderNot(node, replaceSelf) {
    const pill = document.createElement('div');
    pill.className = 'predicate-pill not-pill';

    const header = document.createElement('div');
    header.className = 'predicate-group-header';
    const badge = document.createElement('span');
    badge.className = 'predicate-type-badge';
    badge.textContent = 'NOT';
    header.appendChild(badge);
    const actions = document.createElement('div');
    actions.className = 'predicate-pill-actions';
    actions.appendChild(this._removeButton(() => replaceSelf(null), 'Remove NOT (and its condition)'));
    header.appendChild(actions);
    pill.appendChild(header);

    const children = document.createElement('div');
    children.className = 'predicate-children';
    children.appendChild(this._renderNode(node.predicate, (newChild) => {
      if (newChild == null) {
        replaceSelf(null);
      } else {
        node.predicate = newChild;
        replaceSelf(node);
      }
    }));
    pill.appendChild(children);

    return pill;
  }

  _renderGroup(node, replaceSelf) {
    const pill = document.createElement('div');
    pill.className = 'predicate-pill group-pill';

    const header = document.createElement('div');
    header.className = 'predicate-group-header';

    const combinatorSelect = document.createElement('select');
    combinatorSelect.className = 'predicate-combinator-select';
    for (const combinator of ['AND', 'OR']) {
      const option = document.createElement('option');
      option.value = combinator;
      option.textContent = combinator;
      option.selected = combinator === node.type;
      combinatorSelect.appendChild(option);
    }
    combinatorSelect.addEventListener('change', (event) => {
      node.type = event.target.value;
      replaceSelf(node);
    });
    header.appendChild(combinatorSelect);

    const actions = document.createElement('div');
    actions.className = 'predicate-pill-actions';
    actions.appendChild(this._wrapInNotButton(node, replaceSelf));
    actions.appendChild(this._removeButton(() => replaceSelf(null), 'Remove group (and its conditions)'));
    header.appendChild(actions);
    pill.appendChild(header);

    const children = document.createElement('div');
    children.className = 'predicate-children';
    node.predicates.forEach((child, index) => {
      children.appendChild(this._renderNode(child, (newChild) => {
        if (newChild == null) {
          node.predicates.splice(index, 1);
          if (node.predicates.length === 0) {
            replaceSelf(null);
            return;
          }
        } else {
          node.predicates[index] = newChild;
        }
        replaceSelf(node);
      }));
    });

    const addRow = document.createElement('div');
    addRow.className = 'predicate-add-row';
    addRow.appendChild(this._textButton('+ Condition', () => {
      node.predicates.push(this._defaultLeaf());
      replaceSelf(node);
    }));
    addRow.appendChild(this._textButton('+ Group', () => {
      node.predicates.push({ type: 'AND', predicates: [this._defaultLeaf()] });
      replaceSelf(node);
    }));
    children.appendChild(addRow);

    pill.appendChild(children);
    return pill;
  }
}

customElements.define('ntrloc-predicate-builder', NtrlocPredicateBuilder);
