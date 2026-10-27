import Icon from '../icon/Icon'
import './Button.css'

// `icon`, when given, is drawn before the label in the label's own color.
function Button({ variant = 'primary', icon, className = '', type = 'button', children, ...rest }) {
  const classes = ['btn', `btn-${variant}`, icon && 'btn-with-icon', className].filter(Boolean).join(' ')
  return (
    <button type={type} className={classes} {...rest}>
      {icon && <Icon name={icon} size={16} />}
      {children}
    </button>
  )
}

export default Button
