import { useEffect, useState } from 'react'
import Button from '../../controls/button/Button'
import ModifiedDot from '../../controls/modified-dot/ModifiedDot'
import { useDraftMode } from '../../global/viewmodel/useDraftMode'
import { fetchGrantTargets } from './AccessDelegate'
import { describeNotice, describeNoticeOrigin } from './permissions/grantModel'
import { useGrantChanges } from './permissions/useGrantChanges'
import './LiveNotices.css'

const NO_TARGETS = { itemTypes: [], markers: [] }

// In a draft: what has changed in live about this user or group since the draft started, each change in
// words with who made it, and Acknowledge (docs/ntrloc-system-model-notes.md §13.6). Each stays until
// acknowledged, on its own; nothing here stops the draft being applied.
function LiveNotices({ principal, users, groups }) {
  const { draft, acknowledgeNotice } = useDraftMode()
  const notices = useGrantChanges().noticesFor(principal)
  const [targets, setTargets] = useState(NO_TARGETS)
  const [error, setError] = useState(null)
  const [working, setWorking] = useState(false)
  const draftId = draft?.id ?? null
  const needsTargets = notices.some((n) => n.latest.kind.startsWith('grant.'))

  useEffect(() => {
    if (!needsTargets || !draftId) return
    fetchGrantTargets(draftId)
      .then(setTargets)
      .catch((e) => setError(e.message))
  }, [needsTargets, draftId])

  if (notices.length === 0) return null

  async function acknowledge(notice) {
    setWorking(true)
    setError(null)
    try {
      await acknowledgeNotice(notice)
    } catch (e) {
      setError(e.message)
    }
    setWorking(false)
  }

  return (
    <live-notices role="region" aria-label="Changed in live">
      <h3>
        <ModifiedDot kind="notice" />
        Changed in live since this draft started
      </h3>
      <ul>
        {notices.map((notice) => (
          <li key={notice.latest.elementKey}>
            <span className="live-notice-main">
              <span>{describeNotice(notice, { users, groups, targets })}</span>
              <span className="live-notice-origin">{describeNoticeOrigin(notice)}</span>
            </span>
            <Button variant="ghost-secondary" onClick={() => void acknowledge(notice)} disabled={working}>
              Acknowledge
            </Button>
          </li>
        ))}
      </ul>
      {error && <p className="live-notice-error">{error}</p>}
    </live-notices>
  )
}

export default LiveNotices
