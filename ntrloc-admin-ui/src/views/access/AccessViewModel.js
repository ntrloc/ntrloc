import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAccessChanged } from '../../global/viewmodel/useAccessChanged'
import { useDraftMode } from '../../global/viewmodel/useDraftMode'
import { fetchGroups, fetchUsers } from './AccessDelegate'

// The Access screen's own state: which tab is showing (users, groups, or permissions by item type),
// what's selected in each, and the users and groups to list: in a draft, the draft's groups.
// `groups` are the groups there are; `listedGroups` add those the draft deletes (deleted: true), which
// the list shows struck through so they can be restored.
export function useAccessViewModel() {
  const [activeSection, setActiveSection] = useState('users')
  const [selectedUserId, setSelectedUserId] = useState(null)
  const [selectedGroupId, setSelectedGroupId] = useState(null)
  const [focusTab, setFocusTab] = useState(null)

  // Shown from elsewhere (a draft's review dialog): navigation state { focus: { kind, id, tab } } selects that
  // user or group, on that tab. Taken once, then cleared, so going back doesn't repeat it.
  const location = useLocation()
  const navigate = useNavigate()
  const focus = location.state?.focus
  const [focusTaken, setFocusTaken] = useState(null)
  if (focus && focusTaken !== location.key) {
    setFocusTaken(location.key)
    setActiveSection(focus.kind === 'user' ? 'users' : 'groups')
    if (focus.kind === 'user') setSelectedUserId(focus.id)
    else setSelectedGroupId(focus.id)
    setFocusTab(focus.tab)
  }
  useEffect(() => {
    if (focus) navigate(location.pathname, { replace: true, state: null })
  }, [focus, location.pathname, navigate])
  const [users, setUsers] = useState([])
  const [listedGroups, setListedGroups] = useState([])
  const [status, setStatus] = useState('loading')
  const { draft, draftRevision } = useDraftMode()
  const draftId = draft?.id ?? null

  useEffect(() => {
    Promise.all([fetchUsers(), fetchGroups(draftId)])
      .then(([loadedUsers, loadedGroups]) => {
        setUsers(loadedUsers)
        setListedGroups(loadedGroups)
        setStatus('ready')
      })
      .catch(() => setStatus('error'))
  }, [draftId, draftRevision])

  // After a user's details change (their name or admin flag show in the list).
  const reloadUsers = useCallback(() => fetchUsers().then(setUsers), [])
  // After a group is renamed or moved (the list, and every group tree, show those).
  const reloadGroups = useCallback(() => fetchGroups(draftId).then(setListedGroups), [draftId])
  // Another tab or admin changed users or groups.
  useAccessChanged(() => {
    void reloadUsers()
    void reloadGroups()
  })

  return {
    status,
    reloadUsers,
    reloadGroups,
    activeSection,
    setActiveSection,
    users,
    groups: listedGroups.filter((g) => !g.deleted),
    listedGroups,
    selectedUserId,
    focusTab,
    selectUser: (id) => {
      setFocusTab(null)
      setSelectedUserId(id)
    },
    selectedGroupId,
    selectGroup: (id) => {
      setFocusTab(null)
      setSelectedGroupId(id)
    },
  }
}
