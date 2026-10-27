import { useEffect, useId } from 'react'
import './Dialog.css'

// A modal dialog with a title, a body and a row of action buttons, for anything more than
// ModalPopupView's yes/no confirmation. Escape counts as cancel; a click on the backdrop doesn't, so
// a stray click can't throw away what's been typed into a form. The caller decides when it's shown
// by rendering it or not, so each opening starts with fresh state.
function Dialog({ title, onCancel, actions, children }) {
  const titleId = useId()

  useEffect(() => {
    function handleKeyDown(e) {
      if (e.key === 'Escape') onCancel()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onCancel])

  return (
    <dialog-backdrop>
      <dialog-view role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <h3 id={titleId} className="dialog-title">
          {title}
        </h3>
        <dialog-body>{children}</dialog-body>
        <dialog-actions>{actions}</dialog-actions>
      </dialog-view>
    </dialog-backdrop>
  )
}

export default Dialog
