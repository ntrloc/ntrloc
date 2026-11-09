import { useCallback, useEffect, useState } from 'react'
import { fetchGroups, fetchUsers } from './AccessDelegate'

// The Access screen's own state: which tab is showing (users, groups, or permissions by item type),
// what's selected in each, and the users and groups to list.
export function useAccessViewModel() {
  const [activeSection, setActiveSection] = useState('users')
  const [selectedUserId, setSelectedUserId] = useState(null)
  const [selectedGroupId, setSelectedGroupId] = useState(null)
  const [users, setUsers] = useState([])
  const [groups, setGroups] = useState([])
  const [status, setStatus] = useState('loading')

  useEffect(() => {
    Promise.all([fetchUsers(), fetchGroups()])
      .then(([loadedUsers, loadedGroups]) => {
        setUsers(loadedUsers)
        setGroups(loadedGroups)
        setStatus('ready')
      })
      .catch(() => setStatus('error'))
  }, [])

  // After a user's details change (their name or admin flag show in the list).
  const reloadUsers = useCallback(() => fetchUsers().then(setUsers), [])
  // After a group is renamed or moved (the list, and every group tree, show those).
  const reloadGroups = useCallback(() => fetchGroups().then(setGroups), [])

  return {
    status,
    reloadUsers,
    reloadGroups,
    activeSection,
    setActiveSection,
    users,
    groups,
    selectedUserId,
    selectUser: setSelectedUserId,
    selectedGroupId,
    selectGroup: setSelectedGroupId,
  }
}
