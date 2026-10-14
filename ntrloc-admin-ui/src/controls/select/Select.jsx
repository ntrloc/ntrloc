import ChevronDownIcon from '../chevron-icon/ChevronDownIcon'
import './Select.css'

// Wraps a native <select> with its own chevron (the native arrow is hidden -- see Select.css's
// own appearance: none) rather than relying on the browser's own rendering, so this matches
// PopoverButton/TreeSelect's identical ChevronDownIcon exactly instead of approximating it.
function Select({ className = '', children, ...rest }) {
  const classes = ['select', className].filter(Boolean).join(' ')
  return (
    <select-control>
      <select className={classes} {...rest}>
        {children}
      </select>
      <ChevronDownIcon className="select-caret" />
    </select-control>
  )
}

export default Select
