import CollapsibleCard from '../../../controls/collapsible-card/CollapsibleCard'
import FieldLabel from '../../../controls/field-label/FieldLabel'
import IconButton from '../../../controls/icon-button/IconButton'
import Input from '../../../controls/input/Input'
import DefinitionActions from '../DefinitionActions'
import ChoiceField from '../item-type/ChoiceField'
import EditMessages from '../EditMessages'
import { useControlledListDetailViewModel } from './ControlledListDetailViewModel'
import '../EntityOverviewList.css'
import './ControlledListDetailView.css'

// A controlled list's Overview -- including the properties using it, each "owner · property", the
// owner a link where it's an item type or trait -- and its Values, as cards like an item type's, edited
// the same way: Edit opens them for changes, which are held until Save or Cancel. Which cards are
// collapsed is kept by the caller, under keys of the list's own.
function ControlledListDetailView({
  controlledListViewModels,
  listId,
  navigation,
  onSelectItemType,
  onSelectTrait,
  collapsed,
  onToggleCollapsed,
}) {
  const vm = useControlledListDetailViewModel(controlledListViewModels, listId, navigation)
  const { list, editing, overview } = vm

  if (!list) return <p>Controlled list not found.</p>

  const cards = [
    {
      key: 'overview',
      label: 'Overview',
      view: (
        <controlled-list-overview>
          <Input name="Name" editable={editing} value={overview.fields.name}>
            <input
              className="input-control-field"
              value={overview.fields.name}
              onChange={(e) => overview.setName(e.target.value)}
            />
            <FieldProblem message={overview.nameProblem} />
          </Input>
          {editing && overview.typeEditable ? (
            <ChoiceField
              name="Value type"
              options={overview.typeOptions}
              value={overview.fields.valueType}
              onChange={overview.setValueType}
            />
          ) : (
            <Input name="Value type" editable={false} value={overview.typeLabel} />
          )}
          <UsedBy usedBy={overview.usedBy} onSelectItemType={onSelectItemType} onSelectTrait={onSelectTrait} />
        </controlled-list-overview>
      ),
    },
    {
      key: 'values',
      label: 'Values',
      actions: vm.values.editable && (
        <IconButton icon="plus" label="Add value" variant="secondary" onClick={vm.values.add} />
      ),
      view: <ValuesTable values={vm.values} />,
    },
  ]

  const cardKey = (key) => `controlled-list-${key}`
  return (
    <controlled-list-detail-view>
      <controlled-list-detail-header>
        <h2>{list.name}</h2>
        <DefinitionActions vm={vm} name={list.name} />
      </controlled-list-detail-header>
      <EditMessages vm={vm} />
      <controlled-list-detail-cards>
        {cards.map((card) => (
          <CollapsibleCard
            key={card.key}
            title={card.label}
            actions={card.actions}
            expanded={!collapsed.has(cardKey(card.key))}
            onToggle={() => onToggleCollapsed(cardKey(card.key))}
          >
            {card.view}
          </CollapsibleCard>
        ))}
      </controlled-list-detail-cards>
    </controlled-list-detail-view>
  )
}

function UsedBy({ usedBy, onSelectItemType, onSelectTrait }) {
  const openOwner = { ITEM: onSelectItemType, TRAIT: onSelectTrait }
  return (
    <input-control>
      <FieldLabel>Used by</FieldLabel>
      {usedBy.length === 0 ? (
        <span className="input-control-value">None</span>
      ) : (
        <ul className="entity-overview-link-list controlled-list-usage">
          {usedBy.map((usage) => {
            const open = openOwner[usage.ownerKind]
            return (
              <li key={usage.propertyId}>
                {open ? (
                  <button type="button" className="entity-overview-link" onClick={() => open(usage.ownerId)}>
                    {usage.ownerLabel}
                  </button>
                ) : (
                  usage.ownerLabel
                )}
                {` · ${usage.propertyName}`}
              </li>
            )
          })}
        </ul>
      )}
    </input-control>
  )
}

// `values` is the view model's Values slice: the rows as edited, and, while editing, what changes
// them. A value marked for removal stays, struck through, until Save.
function ValuesTable({ values }) {
  if (values.status === 'loading' || values.status === 'idle') return <p>Loading…</p>
  if (values.status === 'error') return <p className="entity-overview-empty">Couldn't load the values.</p>
  if (values.rows.length === 0) return <p className="entity-overview-empty">No values yet.</p>
  const { editable } = values
  return (
    <table className="controlled-list-table" data-editable={editable || undefined}>
      <thead>
        <tr>
          <th>Value</th>
          <th>Label</th>
          {editable && <th aria-label="Actions" />}
        </tr>
      </thead>
      <tbody>
        {values.rows.map((row, index) => (
          <tr
            key={row.key}
            data-marked-for-delete={row.removed || undefined}
            onBlur={(e) => {
              if (!e.currentTarget.contains(e.relatedTarget)) values.leave(row.key)
            }}
          >
            {editable && !row.removed ? (
              <>
                <td>
                  <input
                    className="input-control-field"
                    aria-label={`Value ${index + 1}`}
                    value={row.value}
                    autoFocus={row.isNew && row.value === '' && row.label === ''}
                    onChange={(e) => values.setValue(row.key, 'value', e.target.value)}
                  />
                  <FieldProblem message={row.problems.value} />
                </td>
                <td>
                  <input
                    className="input-control-field"
                    aria-label={`Label ${index + 1}`}
                    value={row.label}
                    onChange={(e) => values.setValue(row.key, 'label', e.target.value)}
                  />
                  <FieldProblem message={row.problems.label} />
                </td>
              </>
            ) : (
              <>
                <td>{row.value}</td>
                <td>{row.label}</td>
              </>
            )}
            {editable && (
              <td className="controlled-list-actions">
                <IconButton
                  icon={row.removed ? 'undo' : 'trash'}
                  label={row.removed ? `Keep ${row.value}` : `Remove ${row.value || `value ${index + 1}`}`}
                  onClick={() => values.toggleRemove(row.key)}
                />
              </td>
            )}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

// What's wrong with the field just above, if anything.
function FieldProblem({ message }) {
  return message ? <p className="controlled-list-field-problem">{message}</p> : null
}

export default ControlledListDetailView
