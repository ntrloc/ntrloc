import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Button from '../controls/button/Button'
import Dialog from '../controls/dialog/Dialog'
import ModifiedDot from '../controls/modified-dot/ModifiedDot'
import { reconcileChange } from '../global/delegate/DraftDelegate'
import { useDraftMode } from '../global/viewmodel/useDraftMode'
import { fetchGrantTargets, fetchGroups, fetchUsers } from '../views/access/AccessDelegate'
import ReconcileItems from '../views/access/groups/ReconcileItems'
import { useGroupReconcile } from '../views/access/groups/useGroupReconcile'
import {
  describeNotice,
  describeNoticeOrigin,
  describePermission,
  describeReason,
  noticeFocus,
} from '../views/access/permissions/grantModel'
import './ReconcileDialog.css'

const PRINCIPAL_GONE = new Set(['PRINCIPAL_DELETED', 'TARGET_DELETED'])

// Everything live has done that this draft should know about (docs/ntrloc-system-model-notes.md §13.4,
// §13.6), in two lists:
// - Needs reconciling: changes in the draft that live has moved underneath. Keep draft change (now
//   depending on live as it is), or Preserve live change; one whose user, group or target is gone can only
//   be dropped. The draft can't be applied until this list is empty.
// - Changed in live: everything else live changed since the draft started, each acknowledged on its own.
// Show goes to the place in Access it concerns. Group and membership items also show there, beside what
// they're about; here they're listed with the same choices.
function ReconcileDialog({ onClose }) {
  const { draft, grantChanges, groupChanges, membershipChanges, notices, needsReconciling, reloadChanges, acknowledgeNotice, draftRevision } =
    useDraftMode()
  const groupReconcile = useGroupReconcile()
  const navigate = useNavigate()
  const [names, setNames] = useState(null)
  const [error, setError] = useState(null)
  const [working, setWorking] = useState(false)
  const items = grantChanges.filter((c) => c.reconcile.length > 0)
  const groupItems = groupChanges.filter((c) => c.reconcile.length > 0)
  const membershipItems = membershipChanges.filter((c) => c.reconcile.length > 0)

  useEffect(() => {
    Promise.all([fetchGrantTargets(draft.id), fetchUsers(), fetchGroups(draft.id)])
      .then(([targets, users, groups]) => setNames({ targets, users, groups }))
      .catch((e) => setError(e.message))
  }, [draft.id, draftRevision])

  async function act(action) {
    setWorking(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e.message)
    }
    setWorking(false)
  }

  function show(focus) {
    onClose()
    navigate('/access', { state: { focus } })
  }

  function principalName({ kind, id }) {
    if (kind === 'user') return names.users.find((u) => u.id === id)?.displayName ?? 'A deleted user'
    return names.groups.find((g) => g.id === id)?.name ?? 'A deleted group'
  }

  return (
    <Dialog
      title={`Review: ${draft.name}`}
      onCancel={onClose}
      actions={
        <Button variant="secondary" onClick={onClose}>
          Done
        </Button>
      }
    >
      {names === null && !error && <p className="reconcile-muted">Loading…</p>}
      {names !== null && (
        <>
          <h4 className="reconcile-heading">
            <ModifiedDot kind="reconcile" />
            Needs reconciling ({needsReconciling})
          </h4>
          {groupItems.length + membershipItems.length > 0 && (
            <ul className="reconcile-rows">
              {groupItems.map((change) => {
                const group = names.groups.find((g) => g.id === change.groupId)
                return (
                  <li key={change.groupId} className="reconcile-group-row">
                    <span className="reconcile-row-main">
                      <span>Group {group?.name ?? change.name ?? 'deleted in live'}</span>
                      <ReconcileItems
                        reasons={change.reconcile}
                        working={groupReconcile.working}
                        names={names}
                        onChoose={(reason, choice) => void groupReconcile.chooseForGroup(change.groupId, reason, choice)}
                      />
                    </span>
                    {group && (
                      <reconcile-actions>
                        <Button variant="ghost-secondary" onClick={() => show({ kind: 'group', id: change.groupId, tab: 'overview' })}>
                          Show
                        </Button>
                      </reconcile-actions>
                    )}
                  </li>
                )
              })}
              {membershipItems.map((change) => (
                <li key={`${change.groupId}|${change.userId}`} className="reconcile-group-row">
                  <span className="reconcile-row-main">
                    <span>
                      {names.users.find((u) => u.id === change.userId)?.displayName ?? 'A deleted user'} added to{' '}
                      {names.groups.find((g) => g.id === change.groupId)?.name ?? 'a deleted group'}
                    </span>
                    <ReconcileItems
                      reasons={change.reconcile}
                      working={groupReconcile.working}
                      names={names}
                      onChoose={(_, choice) => void groupReconcile.chooseForMembership(change, choice)}
                    />
                  </span>
                </li>
              ))}
            </ul>
          )}
          {groupReconcile.error && <p className="reconcile-error">{groupReconcile.error}</p>}
          {needsReconciling === 0 ? (
            <p className="reconcile-muted">Nothing needs reconciling.</p>
          ) : items.length === 0 ? null : (
            <>
              <p className="reconcile-hint">
                Live has changed underneath these changes since they were made, so they may no longer do what you tested.
                The draft can't be applied until each is settled.
              </p>
              <ul className="reconcile-rows">
                {items.map((change) => {
                  const gone = change.reconcile.some((r) => PRINCIPAL_GONE.has(r.kind))
                  return (
                    <li key={`${change.principal.id}|${JSON.stringify(change.permission)}`}>
                      <span className="reconcile-row-main">
                        <span>
                          {principalName(change.principal)}: {describePermission(change.permission, names.targets)}{' '}
                          {change.value ? 'granted' : 'removed'}
                        </span>
                        {change.reconcile.map((reason) => (
                          <span key={`${reason.kind}-${reason.id}`} className="reconcile-muted">
                            {describeReason(reason, change.principal.kind)}
                          </span>
                        ))}
                      </span>
                      <reconcile-actions>
                        {!gone && (
                          <Button variant="ghost-secondary" onClick={() => show({ ...change.principal, tab: 'permissions' })}>
                            Show
                          </Button>
                        )}
                        {!gone && (
                          <Button
                            variant="ghost-secondary"
                            disabled={working}
                            onClick={() => void act(() => reconcileChange(draft.id, change.principal, change.permission, 'KEEP').then(reloadChanges))}
                          >
                            Keep draft change
                          </Button>
                        )}
                        <Button
                          variant="ghost-secondary"
                          disabled={working}
                          onClick={() => void act(() => reconcileChange(draft.id, change.principal, change.permission, 'USE_LIVE').then(reloadChanges))}
                        >
                          {gone ? 'Drop' : 'Preserve live change'}
                        </Button>
                      </reconcile-actions>
                    </li>
                  )
                })}
              </ul>
            </>
          )}

          <h4 className="reconcile-heading">
            <ModifiedDot kind="notice" />
            Changed in live ({notices.length})
          </h4>
          {notices.length === 0 ? (
            <p className="reconcile-muted">Nothing to review.</p>
          ) : (
            <ul className="reconcile-rows">
              {notices.map((notice) => {
                const focus = noticeFocus(notice)
                return (
                  <li key={notice.latest.elementKey}>
                    <span className="reconcile-row-main">
                      <span>{describeNotice(notice, names)}</span>
                      <span className="reconcile-muted">{describeNoticeOrigin(notice)}</span>
                    </span>
                    <reconcile-actions>
                      {focus && (
                        <Button variant="ghost-secondary" onClick={() => show(focus)}>
                          Show
                        </Button>
                      )}
                      <Button variant="ghost-secondary" disabled={working} onClick={() => void act(() => acknowledgeNotice(notice))}>
                        Acknowledge
                      </Button>
                    </reconcile-actions>
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}
      {error && <p className="reconcile-error">{error}</p>}
    </Dialog>
  )
}

export default ReconcileDialog
