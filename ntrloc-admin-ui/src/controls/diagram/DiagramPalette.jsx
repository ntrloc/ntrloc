import { useEffect, useRef, useState } from 'react'
import './DiagramPalette.css'

// How far a press on an item has to move to be a drag rather than a click.
const DRAG_THRESHOLD = 4
// How long a flyout stays open after the pointer leaves the palette, so crossing the gap between a
// category and its flyout doesn't close it.
const CLOSE_DELAY_MS = 250

// A diagram editor's palette, on the left of the diagram: a strip of categories, each opening a
// flyout of the things it adds when it's hovered (or clicked). An item is dragged onto the canvas, or clicked and then placed with a
// click on the canvas (Escape, or a click anywhere else, puts it down again).
//
// categories: [{ id, label, icon, items: [{ id, label, icon, description }] }], icons being small SVG
// elements. Hovering an item with a description shows it in a card beside the flyout.
// targetRef: the canvas element items are placed on. onPlace(item, { clientX, clientY }): where one
// was placed.
function DiagramPalette({ categories, targetRef, onPlace }) {
  const [openId, setOpenId] = useState(null)
  // The item being dragged or placed, and where the pointer is: { item, x, y }.
  const [carrying, setCarrying] = useState(null)
  // The item being hovered in the flyout, and how far down the flyout it is: { item, top }.
  const [hovered, setHovered] = useState(null)
  const paletteRef = useRef(null)
  const closeTimer = useRef(null)

  function openCategory(id) {
    clearTimeout(closeTimer.current)
    setHovered(null)
    setOpenId(id)
  }

  function closeSoon() {
    clearTimeout(closeTimer.current)
    closeTimer.current = setTimeout(() => setOpenId(null), CLOSE_DELAY_MS)
  }

  useEffect(() => () => clearTimeout(closeTimer.current), [])

  const overTarget = (x, y) => {
    const rect = targetRef.current?.getBoundingClientRect()
    return !!rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
  }

  // An open flyout closes on a press anywhere outside the palette, or Escape.
  useEffect(() => {
    if (!openId || carrying) return
    function onPointerDown(event) {
      if (!paletteRef.current?.contains(event.target)) setOpenId(null)
    }
    function onKeyDown(event) {
      if (event.key === 'Escape') setOpenId(null)
    }
    window.addEventListener('pointerdown', onPointerDown)
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('pointerdown', onPointerDown)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [openId, carrying])

  // Pressing an item: a drag if the pointer moves, placed where it's let go on the canvas; a click
  // otherwise, after which the item follows the pointer until the next press places it.
  function onItemPointerDown(event, item) {
    if (event.button !== 0) return
    event.preventDefault()
    const startX = event.clientX
    const startY = event.clientY
    let dragging = false

    function move(e) {
      if (!dragging && Math.hypot(e.clientX - startX, e.clientY - startY) < DRAG_THRESHOLD) return
      dragging = true
      setCarrying({ item, x: e.clientX, y: e.clientY })
    }
    function up(e) {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      if (dragging) {
        setCarrying(null)
        setOpenId(null)
        if (overTarget(e.clientX, e.clientY)) onPlace(item, { clientX: e.clientX, clientY: e.clientY })
      } else {
        placeWithNextClick(item, e)
      }
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }

  function placeWithNextClick(item, from) {
    setOpenId(null)
    setCarrying({ item, x: from.clientX, y: from.clientY })
    function move(e) {
      setCarrying({ item, x: e.clientX, y: e.clientY })
    }
    // In the capture phase, so the press that places it doesn't also select or pan on the canvas.
    function press(e) {
      e.preventDefault()
      e.stopPropagation()
      finish()
      if (overTarget(e.clientX, e.clientY)) onPlace(item, { clientX: e.clientX, clientY: e.clientY })
    }
    function key(e) {
      if (e.key === 'Escape') finish()
    }
    function finish() {
      setCarrying(null)
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerdown', press, true)
      window.removeEventListener('keydown', key)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerdown', press, true)
    window.addEventListener('keydown', key)
  }

  const open = categories.find((c) => c.id === openId)
  return (
    <diagram-palette ref={paletteRef} onPointerEnter={() => clearTimeout(closeTimer.current)} onPointerLeave={closeSoon}>
      {categories.map((category) => (
        <button
          key={category.id}
          type="button"
          className="diagram-palette-category"
          aria-label={category.label}
          aria-expanded={category.id === openId}
          title={category.label}
          onPointerEnter={() => openCategory(category.id)}
          onClick={() => openCategory(category.id)}
        >
          {category.icon}
        </button>
      ))}
      {open && (
        <diagram-palette-flyout role="menu" aria-label={open.label}>
          <span className="diagram-palette-flyout-title">{open.label}</span>
          {open.items.map((item) => (
            <button
              key={item.id}
              type="button"
              role="menuitem"
              className="diagram-palette-item"
              onPointerDown={(event) => onItemPointerDown(event, item)}
              onPointerEnter={(event) => setHovered({ item, top: event.currentTarget.offsetTop })}
              onPointerLeave={() => setHovered(null)}
            >
              {item.icon}
              <span>{item.label}</span>
            </button>
          ))}
          {hovered?.item.description && !carrying && (
            <diagram-palette-tip style={{ top: hovered.top }} role="tooltip">
              <strong>{hovered.item.label}</strong>
              <span>{hovered.item.description}</span>
            </diagram-palette-tip>
          )}
        </diagram-palette-flyout>
      )}
      {carrying && (
        <diagram-palette-ghost style={{ left: carrying.x, top: carrying.y }}>
          {carrying.item.icon}
          <span>{carrying.item.label}</span>
        </diagram-palette-ghost>
      )}
    </diagram-palette>
  )
}

export default DiagramPalette
