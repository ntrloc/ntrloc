import './Button.css'

function Button({ variant = 'primary', className = '', type = 'button', children, ...rest }) {
  const classes = ['btn', `btn-${variant}`, className].filter(Boolean).join(' ')
  return (
    <button type={type} className={classes} {...rest}>
      {children}
    </button>
  )
}

export default Button
