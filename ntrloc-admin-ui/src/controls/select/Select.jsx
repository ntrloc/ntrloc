import './Select.css'

function Select({ className = '', children, ...rest }) {
  const classes = ['select', className].filter(Boolean).join(' ')
  return (
    <select className={classes} {...rest}>
      {children}
    </select>
  )
}

export default Select
