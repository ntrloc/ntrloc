import Icon from '../../../controls/icon/Icon'
import { MODIFIED_LABEL, RECONCILE_LABEL } from '../../../controls/modified-dot/ModifiedDot'

// One permission, as a small toggle. Filled: granted directly. Outlined: granted only through a
// group (the tooltip says which). Dim: not granted. Clicking changes the direct grant alone, so a
// group's grant stays as it is. `mark`: the current draft changes this one, 'modified' or, where live has
// moved underneath the change, 'reconcile' (a corner dot either way).
function pillState(direct, viaGroups) {
  if (direct) return 'direct'
  return viaGroups.length > 0 ? 'inherited' : 'none'
}

function PermissionPill({ label, sources, editable, mark, onToggle }) {
  const viaGroups = sources.groups.map((g) => g.name)
  const state = pillState(sources.direct, viaGroups)
  const parts = []
  if (sources.direct) parts.push('Granted directly')
  if (viaGroups.length > 0) parts.push(`Through ${viaGroups.join(', ')}`)
  if (parts.length === 0) parts.push('Not granted')
  if (mark) parts.push(mark === 'reconcile' ? RECONCILE_LABEL : MODIFIED_LABEL)
  const title = parts.join('. ') + '.'

  return (
    <button
      type="button"
      className="permission-pill"
      data-state={state}
      data-modified={mark || undefined}
      aria-pressed={sources.direct}
      aria-label={`${label}: ${title}`}
      title={title}
      disabled={!editable}
      onClick={onToggle}
    >
      {state !== 'none' && <Icon name="check" size={12} />}
      {label}
    </button>
  )
}

export default PermissionPill
