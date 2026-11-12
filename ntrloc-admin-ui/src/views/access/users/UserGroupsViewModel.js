import { useState } from 'react'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { addUserToGroup, removeUserFromGroup } from '../AccessDelegate'
import { groupHierarchy } from '../permissions/grantModel'

const EVERYONE = 'everyone'

// The Group membership tab: the whole group hierarchy, ticked where the user was added directly.
// CheckboxTree's selectUpward shows the groups above those as implied. In a draft, the draft's groups
// and memberships.
export function useUserGroupsViewModel({ detail, allGroups, reload }) {
  const { user, groups } = detail
  const { draft, reloadChanges } = useDraftMode()
  const draftId = draft?.id ?? null
  const [error, setError] = useState(null)

  const directGroupIds = groups.map((g) => g.id)
  const directGroupKeys = new Set(directGroupIds)
  // Everyone is in everyone.
  const groupTree = groupHierarchy(allGroups, (g) => ({ disabled: g.name === EVERYONE }))

  async function run(action) {
    setError(null)
    try {
      await action()
      await reload()
      reloadChanges()
    } catch (e) {
      setError(e.message)
    }
  }

  // A click changes one membership; find which and make that call.
  function setDirectGroups(next) {
    const joined = [...next].find((id) => !directGroupKeys.has(id))
    const left = directGroupIds.find((id) => !next.has(id))
    if (joined) void run(() => addUserToGroup(draftId, user.id, joined))
    else if (left) void run(() => removeUserFromGroup(draftId, user.id, left))
  }

  return { groupTree, directGroupKeys, setDirectGroups, error }
}
