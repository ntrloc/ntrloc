import Icon from '../../../controls/icon/Icon'
import { MODIFIED_LABEL } from '../../../controls/modified-dot/ModifiedDot'

// One permission, as a small toggle. Filled: granted directly. Outlined: granted only through a
// group (the tooltip says which). Dim: not granted. Clicking changes the direct grant alone, so a
// group's grant stays as it is. `modified`: the current draft changes this one (a corner dot).
function pillState(direct, viaGroups) {
  if (direct) return 'direct'
  return viaGroups.length > 0 ? 'inherited' : 'none'
}

function PermissionPill({ label, sources, editable, modified, onToggle }) {
  const viaGroups = sources.groups.map((g) => g.name)
  const state = pillState(sources.direct, viaGroups)
  const parts = []
  if (sources.direct) parts.push('Granted directly')
  if (viaGroups.length > 0) parts.push(`Through ${viaGroups.join(', ')}`)
  if (parts.length === 0) parts.push('Not granted')
  if (modified) parts.push(MODIFIED_LABEL)
  const title = parts.join('. ') + '.'

  return (
    <button
      type="button"
      className="permission-pill"
      data-state={state}
      data-modified={modified || undefined}
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
