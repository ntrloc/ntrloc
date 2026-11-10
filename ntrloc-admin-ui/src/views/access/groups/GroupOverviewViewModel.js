import { useState } from 'react'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { addUserToGroup, removeUserFromGroup, renameGroup, setGroupParent } from '../AccessDelegate'
import { groupHierarchy } from '../permissions/grantModel'

const EVERYONE = 'everyone'

// The group Info / Members tab's state and edits: its name and parent (edited together, with Save),
// and its members. Groups and membership aren't drafted yet (Phase 2, docs/ntrloc-drafts-plan.md), so
// in draft mode it's all read-only.
export function useGroupOverviewViewModel({ detail, allGroups, users, reload, onGroupsChanged }) {
  const { group, members } = detail
  const isEveryone = group.name === EVERYONE
  const { inDraft } = useDraftMode()
  const parentId = group.parentIds[0] ?? null

  const [form, setForm] = useState(null)
  const [error, setError] = useState(null)
  const editing = form !== null

  async function run(action, { groupsChanged = false } = {}) {
    setError(null)
    try {
      await action()
      await reload()
      if (groupsChanged) await onGroupsChanged()
      return true
    } catch (e) {
      setError(e.message)
      return false
    }
  }

  // The group can't sit under itself or anything below it, so those are locked in the parent tree.
  const below = (id) => allGroups.filter((g) => g.parentIds.includes(id)).flatMap((g) => [g.id, ...below(g.id)])
  const locked = new Set([group.id, ...below(group.id)])
  const parentTree = groupHierarchy(allGroups, (g) => ({ disabled: locked.has(g.id) }))
  const nameOf = (id) => allGroups.find((g) => g.id === id)?.name ?? ''

  async function save() {
    const name = form.name.trim()
    const saved = await run(
      async () => {
        if (name !== group.name) await renameGroup(group.id, name)
        if (form.parentId !== parentId) await setGroupParent(group.id, form.parentId)
      },
      { groupsChanged: true },
    )
    if (saved) setForm(null)
  }

  const memberIds = new Set(members.map((m) => m.id))

  return {
    group,
    members,
    isEveryone,
    editing,
    inDraft,
    // 'everyone' is fixed, and nothing here is drafted yet.
    canEdit: !isEveryone && !inDraft,
    name: editing ? form.name : group.name,
    setName: (name) => setForm((current) => ({ ...current, name })),
    parentName: nameOf(editing ? form.parentId : parentId),
    parentKeys: new Set([editing ? form.parentId : parentId]),
    setParent: (next) => setForm((current) => ({ ...current, parentId: [...next][0] })),
    parentTree,
    nameError: editing && form.name.trim() === '' ? 'A group needs a name.' : null,
    startEdit: () => {
      setError(null)
      setForm({ name: group.name, parentId })
    },
    cancelEdit: () => setForm(null),
    save,
    error,
    joinableUsers: users.filter((u) => !memberIds.has(u.id)),
    addMember: (userId) => run(() => addUserToGroup(userId, group.id)),
    removeMember: (userId) => run(() => removeUserFromGroup(userId, group.id)),
  }
}
