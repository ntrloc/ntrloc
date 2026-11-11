import CollapsibleCard, { TriangleIcon } from '../../../controls/collapsible-card/CollapsibleCard'
import ModifiedDot from '../../../controls/modified-dot/ModifiedDot'
import { usePermissionsViewModel } from './PermissionsViewModel'
import PermissionPill from './PermissionPill'
import './PermissionsTab.css'

const has = (sources) => sources.direct || sources.groups.length > 0

// Everything a marker grant covers, for one marker: each entry is a labelled set of permissions.
function markerAreas(itemType, markerId) {
  const m = (area, targetId, op) => ({ kind: 'marker', markerId, area, targetId, op })
  const areas = [{ title: 'Items', rows: [{ permissions: [['Read', m('item', null, 'read')], ['Delete', m('item', null, 'delete')]] }] }]
  if (itemType.properties.length > 0) {
    areas.push({
      title: 'Properties',
      rows: itemType.properties.map((p) => ({
        label: p.name,
        permissions: [['Read', m('property', p.id, 'read')], ['Write', m('property', p.id, 'write')]],
      })),
    })
  }
  if (itemType.perspectives.length > 0) {
    areas.push({
      title: 'Links',
      rows: itemType.perspectives.map((lp) => ({
        label: lp.name,
        permissions: [['Read', m('perspective', lp.id, 'read')], ['Create', m('perspective', lp.id, 'create')], ['Delete', m('perspective', lp.id, 'delete')]],
      })),
    })
  }
  // A link property is granted once per marker, whichever perspective reaches the link.
  const linkProperties = new Map()
  for (const lp of itemType.perspectives) {
    for (const prop of lp.properties ?? []) if (!linkProperties.has(prop.id)) linkProperties.set(prop.id, `${lp.name} · ${prop.name}`)
  }
  if (linkProperties.size > 0) {
    areas.push({
      title: 'Link properties',
      rows: [...linkProperties].map(([id, label]) => ({
        label,
        permissions: [['Read', m('linkProperty', id, 'read')], ['Write', m('linkProperty', id, 'write')]],
      })),
    })
  }
  for (const sm of itemType.stateMachines ?? []) {
    areas.push({
      title: sm.name,
      rows: [{ permissions: [['Start', m('start', sm.id)], ...sm.transitions.map((t) => [t.name, m('transition', t.id)])] }],
    })
  }
  return areas
}

// Areas summarised by how many of their rows grant anything.
const COUNTED_AREAS = { Properties: ['property', 'properties'], Links: ['link', 'links'], 'Link properties': ['link property', 'link properties'] }

// A marker's one-line summary of what's granted, so a closed row still says something useful.
function markerSummary(areas, sourcesOf) {
  const parts = []
  for (const area of areas) {
    const granted = area.rows.flatMap((row) => row.permissions.filter(([, p]) => has(sourcesOf(p))).map(([label]) => label))
    if (granted.length === 0) continue
    if (area.title === 'Items') parts.push(granted.join(' & '))
    else if (COUNTED_AREAS[area.title]) {
      const rows = area.rows.filter((row) => row.permissions.some(([, p]) => has(sourcesOf(p)))).length
      const [one, many] = COUNTED_AREAS[area.title]
      parts.push(`${rows} ${rows === 1 ? one : many}`)
    } else parts.push(`${area.title}: ${granted.join(', ')}`)
  }
  return parts.length > 0 ? parts.join(' · ') : 'No access'
}

function MarkerRow({ marker, itemType, vm, editable }) {
  const areas = markerAreas(itemType, marker.id)
  const open = vm.expandedMarkerId === marker.id
  const summary = markerSummary(areas, vm.sourcesOf)
  return (
    <li className="permissions-marker" data-open={open || undefined}>
      <button type="button" className="permissions-marker-toggle" aria-expanded={open} onClick={() => vm.toggleMarker(marker.id)}>
        <TriangleIcon expanded={open} />
        {/* The dot sits inside the name's fixed-width column, so it follows the last word. */}
        <span className="permissions-marker-name">
          {marker.name}
          {vm.markerChanged(marker.id) && <ModifiedDot />}
        </span>
        <span className="permissions-summary" data-empty={summary === 'No access' || undefined}>
          {summary}
        </span>
      </button>
      {open && (
        <permissions-marker-body>
          {areas.map((area) => (
            <permissions-area key={area.title}>
              <h4>{area.title}</h4>
              {area.rows.map((row, i) => (
                <permissions-row key={row.label ?? i}>
                  {row.label && <span className="permissions-row-label">{row.label}</span>}
                  <permissions-pills>
                    {row.permissions.map(([label, permission]) => (
                      <PermissionPill
                        key={label}
                        label={label}
                        sources={vm.sourcesOf(permission)}
                        editable={editable}
                        modified={vm.changed(permission)}
                        onToggle={() => vm.toggle(permission)}
                      />
                    ))}
                  </permissions-pills>
                </permissions-row>
              ))}
            </permissions-area>
          ))}
        </permissions-marker-body>
      )}
    </li>
  )
}

function ItemTypeCard({ itemType, markers, vm, editable }) {
  const typePermissions = [
    { label: 'Read', permission: { kind: 'itemType', itemTypeId: itemType.id, op: 'read' } },
    { label: 'Create', permission: { kind: 'itemType', itemTypeId: itemType.id, op: 'create' } },
  ]
  const granted = typePermissions.filter((t) => has(vm.sourcesOf(t.permission))).map((t) => t.label)
  const markerAccess = markers.filter((mk) => markerSummary(markerAreas(itemType, mk.id), vm.sourcesOf) !== 'No access').length
  const hasAny = granted.length > 0 || markerAccess > 0
  const summary = hasAny
    ? [granted.length > 0 ? granted.join(' & ') : null, markerAccess > 0 ? `${markerAccess} of ${markers.length} markers` : null]
        .filter(Boolean)
        .join(' · ')
    : 'No access'

  return (
    <CollapsibleCard
      title={itemType.name}
      expanded={vm.itemTypeOpen(itemType.id, hasAny)}
      onToggle={() => vm.toggleItemType(itemType.id, hasAny)}
      modified={vm.itemTypeChanged(itemType.id, markers)}
      actions={<span className="permissions-summary" data-empty={!hasAny || undefined}>{summary}</span>}
    >
      <permissions-type-row>
        <span className="permissions-row-label">This item type</span>
        <permissions-pills>
          {typePermissions.map(({ label, permission }) => (
            <PermissionPill
              key={label}
              label={label}
              sources={vm.sourcesOf(permission)}
              editable={editable}
              modified={vm.changed(permission)}
              onToggle={() => vm.toggle(permission)}
            />
          ))}
        </permissions-pills>
      </permissions-type-row>
      {markers.length > 0 && (
        <>
          <h4 className="permissions-markers-title">Items with these markers</h4>
          <ul className="permissions-markers">
            {markers.map((marker) => (
              <MarkerRow key={marker.id} marker={marker} itemType={itemType} vm={vm} editable={editable} />
            ))}
          </ul>
        </>
      )}
    </CollapsibleCard>
  )
}

// What a user or group may do, item type by item type. `inheritedFrom` are the groups whose grants
// also apply (a user's groups; for a group, none yet). `adminNote`, when set, explains that grants
// don't limit an admin.
function PermissionsTab({ principal, inheritedFrom, adminNote, editable = true }) {
  const vm = usePermissionsViewModel(principal, inheritedFrom)
  if (!vm.ready) return vm.error ? <p className="permissions-error">{vm.error}</p> : <p className="permissions-muted">Loading…</p>

  return (
    <permissions-tab>
      <permissions-header>
        {adminNote && <p className="permissions-admin-note">{adminNote}</p>}
        <permissions-legend>
          <span className="permission-pill" data-state="direct" aria-hidden="true">Granted directly</span>
          {inheritedFrom.length > 0 && (
            <span className="permission-pill" data-state="inherited" aria-hidden="true">Through a group</span>
          )}
          <span className="permission-pill" data-state="none" aria-hidden="true">Not granted</span>
          {editable && <span className="permissions-muted">Click to grant or remove directly.</span>}
        </permissions-legend>
        {vm.error && <p className="permissions-error">{vm.error}</p>}
      </permissions-header>
      <permissions-cards>
        {vm.targets.itemTypes.map((itemType) => (
          <ItemTypeCard
            key={itemType.id}
            itemType={itemType}
            markers={vm.targets.markers.filter((mk) => mk.scopeId === itemType.id)}
            vm={vm}
            editable={editable}
          />
        ))}
      </permissions-cards>
    </permissions-tab>
  )
}

export default PermissionsTab
