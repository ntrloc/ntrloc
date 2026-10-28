import CollapsibleCard from '../../../controls/collapsible-card/CollapsibleCard'
import FieldLabel from '../../../controls/field-label/FieldLabel'
import Input from '../../../controls/input/Input'
import { useControlledListDetailViewModel } from './ControlledListDetailViewModel'
import '../EntityOverviewList.css'
import './ControlledListDetailView.css'

// A controlled list's Overview -- including the properties using it, each "owner · property", the
// owner a link where it's an item type or trait -- and its Values, as cards like an item type's.
// Viewing only, for now. Which cards are collapsed is kept by the caller, under keys of the list's own.
function ControlledListDetailView({ listId, onSelectItemType, onSelectTrait, collapsed, onToggleCollapsed }) {
  const vm = useControlledListDetailViewModel(listId)
  const { list } = vm

  if (!list) return <p>Controlled list not found.</p>

  const openOwner = { ITEM: onSelectItemType, TRAIT: onSelectTrait }
  const cards = [
    {
      key: 'overview',
      label: 'Overview',
      view: (
        <controlled-list-overview>
          <Input name="Name" editable={false} value={list.name} />
          <Input name="Value type" editable={false} value={vm.typeLabel} />
          <input-control>
            <FieldLabel>Used by</FieldLabel>
            {vm.usedBy.length === 0 ? (
              <span className="input-control-value">None</span>
            ) : (
              <ul className="entity-overview-link-list controlled-list-usage">
                {vm.usedBy.map((usage) => {
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
        </controlled-list-overview>
      ),
    },
    {
      key: 'values',
      label: 'Values',
      view: <ValuesTable values={vm.values} />,
    },
  ]

  const cardKey = (key) => `controlled-list-${key}`
  return (
    <controlled-list-detail-view>
      <controlled-list-detail-header>
        <h2>{list.name}</h2>
      </controlled-list-detail-header>
      <controlled-list-detail-cards>
        {cards.map((card) => (
          <CollapsibleCard
            key={card.key}
            title={card.label}
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

// `values` is the list's values snapshot: { status, values: [{ value, label }] }.
function ValuesTable({ values }) {
  if (values.status === 'loading' || values.status === 'idle') return <p>Loading…</p>
  if (values.status === 'error') return <p className="entity-overview-empty">Couldn't load the values.</p>
  if (values.values.length === 0) return <p className="entity-overview-empty">No values yet.</p>
  return (
    <table className="controlled-list-table">
      <thead>
        <tr>
          <th>Value</th>
          <th>Label</th>
        </tr>
      </thead>
      <tbody>
        {values.values.map((entry) => (
          <tr key={String(entry.value)}>
            <td>{String(entry.value)}</td>
            <td>{entry.label ?? ''}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export default ControlledListDetailView
