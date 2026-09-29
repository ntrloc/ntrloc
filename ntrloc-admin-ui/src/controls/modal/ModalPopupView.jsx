import { useEffect } from 'react'
import Button from '../button/Button'
import './ModalPopupView.css'

// A generic modal confirmation dialog -- a message plus OK/Cancel, nothing domain-specific about
// what's being confirmed. Cancel is focused by default (autoFocus flows through Button's own
// {...rest} spread onto the native <button>, no ref needed): callers reach for this mainly to
// confirm something destructive, so defaulting focus to the safe action means an accidental
// Enter/Space keypress cancels rather than confirms. Backdrop click and Escape both count as
// cancel too, matching TreeSelect's own click-outside-closes convention. Controlled, like every
// other control here (open/message/onConfirm/onCancel all owned by the caller) -- no internal
// open state of its own, unlike TreeSelect's popover, since a caller always needs to know the
// outcome to act on it.
function ModalPopupView({ open, message, onConfirm, onCancel }) {
  useEffect(() => {
    if (!open) return
    function handleKeyDown(e) {
      if (e.key === 'Escape') onCancel()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open, onCancel])

  if (!open) return null

  return (
    <modal-popup-backdrop
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onCancel()
      }}
    >
      <modal-popup-view role="alertdialog" aria-modal="true">
        <p className="modal-popup-message">{message}</p>
        <modal-popup-actions>
          <Button variant="secondary" autoFocus onClick={onCancel}>
            Cancel
          </Button>
          <Button variant="primary" onClick={onConfirm}>
            OK
          </Button>
        </modal-popup-actions>
      </modal-popup-view>
    </modal-popup-backdrop>
  )
}

export default ModalPopupView
