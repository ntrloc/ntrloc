// A draft's group and membership changes (/changes: groups, memberships), for the trail of marks and for
// showing each item to reconcile beside what it's about (docs/ntrloc-system-model-notes.md §13.4.1).

const NONE = []
const mark = (changes) => (changes.some((c) => c.reconcile.length > 0) ? 'reconcile' : changes.length > 0 ? 'modified' : null)

export function indexGroupChanges(groupChanges, membershipChanges) {
  const groupChange = (id) => groupChanges.find((c) => c.groupId === id) ?? null
  const membershipsOfGroup = (id) => membershipChanges.filter((m) => m.groupId === id)
  const membershipsOfUser = (id) => membershipChanges.filter((m) => m.userId === id)
  const ofGroup = (id) => [groupChange(id), ...membershipsOfGroup(id)].filter(Boolean)
  return {
    kindMark: (kind) => mark(kind === 'group' ? [...groupChanges, ...membershipChanges] : kind === 'user' ? membershipChanges : NONE),
    principalMark: ({ kind, id }) => mark(kind === 'group' ? ofGroup(id) : membershipsOfUser(id)),
    // A group's Info / Members tab, or a user's Groups tab.
    areaMark: ({ kind, id }, area) =>
      kind === 'group' && area === 'overview' ? mark(ofGroup(id)) : kind === 'user' && area === 'membership' ? mark(membershipsOfUser(id)) : null,
    // The reasons to show beside one field of a group: 'name', 'parent', 'deleted' (a group the draft
    // makes has its own under 'created': its name and parent).
    reasonsFor: (groupId, field) => {
      const reasons = groupChange(groupId)?.reconcile ?? NONE
      if (field === 'name') return reasons.filter((r) => r.field === 'name' || (r.field === 'created' && r.kind === 'NAME_TAKEN'))
      if (field === 'parent') return reasons.filter((r) => r.field === 'parent' || (r.field === 'created' && r.kind === 'PARENT_GONE'))
      return reasons.filter((r) => r.field === field)
    },
    // Memberships of this group with something to reconcile: { change, reason } each.
    membershipReasons: (groupId) => membershipsOfGroup(groupId).flatMap((change) => change.reconcile.map((reason) => ({ change, reason }))),
    groupChange,
  }
}

export const CHOICE_LABELS = {
  KEEP: 'Keep draft change',
  USE_LIVE: 'Preserve live change',
  ABANDON: 'Abandon draft change',
  DELETE_SUBGROUPS: 'Delete its new subgroups too',
}

const quoted = (side) => `“${side?.name ?? ''}”`
const named = (side) => side?.name ?? 'a deleted group'

// One reason in words. `created`: the reason is about a group the draft makes.
export function describeGroupReason(reason) {
  const created = reason.field === 'created'
  switch (reason.kind) {
    case 'CONFLICT':
      return reason.field === 'name'
        ? `Renamed in both: draft ${quoted(reason.draft)} · live ${quoted(reason.live)} (${quoted(reason.base)} when the draft started).`
        : `Moved in both: draft under ${named(reason.draft)} · live under ${named(reason.live)} (${named(reason.base)} when the draft started).`
    case 'PARENT_GONE':
      return `${created ? 'The draft makes it' : 'The draft moves it'} under ${named(reason.draft)}, which live has deleted. Choose another parent with Edit, or abandon the change.`
    case 'CYCLE':
      return `Live has moved ${named(reason.draft)} below this group, so moving it there would put it under itself. Choose another parent with Edit, or abandon the move.`
    case 'NAME_TAKEN':
      return `Live now has another group named ${quoted(reason.draft)}. Choose another name with Edit, or abandon the ${created ? 'new group' : 'rename'}.`
    case 'CHANGED_IN_LIVE':
      return 'The draft deletes this group, but live has changed it since:'
    case 'NEW_SUBGROUPS':
      return `The draft deletes this group, but live has added ${reason.groups.length === 1 ? 'a subgroup' : 'subgroups'} under it: ${reason.groups.map((g) => g.name).join(', ')}.`
    case 'GROUP_GONE':
      return 'Live has deleted this group.'
    case 'USER_GONE':
      return 'The draft adds a user here whom live has since deleted.'
    default:
      return 'Live has changed this.'
  }
}
