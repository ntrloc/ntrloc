import { useState } from 'react'
import PaneView from '../PaneView'
import ItemPanePropertyGroupsView from './ItemPanePropertyGroupsView'
import ItemPanePropertyGroupsEditView from './ItemPanePropertyGroupsEditView'
import TabView from '../../../controls/tabview/TabView'
import ModalPopupView from '../../../controls/modal/ModalPopupView'
import { flattenProperties } from '../flattenProperties'
import { deleteItem, updateItem } from '../searchpane/SearchDelegate'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { controlledListDisplayValue } from '../../../global/model/ControlledListValue'
import { useItemPaneViewModel, buildDraft, buildPropertyDiff } from './ItemPaneViewModel'
import './ItemPaneView.css'

function ItemPaneView({ itemTypeName, itemId, onClose, onItemDeleted, maximized, onToggleMaximize }) {
  const { status, item, groupedProperties, editableGroups, reload } = useItemPaneViewModel(itemTypeName, itemId)
  // "Photo: IMG_0988.JPG" -- the item type used to be shown as its own line in the content area;
  // folding it into the header leaves the content area free for just the tabs (see
  // ItemPaneContent) and matches how SearchResultItemView already pairs type + label.
  const title = item ? `${item.itemType}: ${item.displayLabel}` : itemTypeName

  return (
    <PaneView title={title} onClose={onClose} maximized={maximized} onToggleMaximize={onToggleMaximize}>
      {status === 'loading' && <item-pane-view>Loading…</item-pane-view>}
      {status === 'notFound' && <item-pane-view>Item not found.</item-pane-view>}
      {status === 'error' && <item-pane-view>Failed to load item.</item-pane-view>}
      {status === 'ready' && item && (
        <ItemPaneContent
          item={item}
          groupedProperties={groupedProperties}
          editableGroups={editableGroups}
          onItemDeleted={onItemDeleted}
          onSaved={reload}
        />
      )}
    </PaneView>
  )
}

function ItemPaneContent({ item, groupedProperties, editableGroups, onItemDeleted, onSaved }) {
  const { getHasStateMachines, getHasTrait } = useItemTypeModel()
  // File is the trait's own fixed namespace/group name, "content" the fixed BINARY property name
  // within it (see ControlledListManager-adjacent schema: every File-trait item type shares this
  // exact shape) -- so this path is safe to hardcode rather than look up, the same way
  // ItemPaneLinks/States already know their own data's shape without a schema lookup.
  const fileContent = item.properties?.File?.content
  const canPreview = getHasTrait(item.itemType, 'File') && Boolean(fileContent?.url) && isPreviewableMimeType(fileContent?.mimeType)

  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState({})
  const [saving, setSaving] = useState(false)
  const [saveErrors, setSaveErrors] = useState(null)
  // Matches sections' own first-entry rule below (Preview first when present) -- a lazy initializer
  // since canPreview is already known by the time this mounts, not something to react to afterward.
  const [activeTabKey, setActiveTabKey] = useState(() => (canPreview ? 'preview' : 'properties'))
  const linkEntries = Object.entries(item.links ?? {})
  const stateEntries = Object.entries(item.states ?? {})

  async function handleConfirmDelete() {
    setConfirmingDelete(false)
    await deleteItem(item.itemId)
    onItemDeleted?.(item.itemId)
  }

  // Jumps to the Properties tab too -- Edit is otherwise easy to click from Links/Markers/etc and
  // land on a tab that isn't the one that just became editable.
  function handleEditClick() {
    setDraft(buildDraft(editableGroups))
    setSaveErrors(null)
    setEditing(true)
    setActiveTabKey('properties')
  }

  function handleCancelClick() {
    setEditing(false)
    setDraft({})
    setSaveErrors(null)
  }

  function handleFieldChange(key, value) {
    setDraft((current) => ({ ...current, [key]: value }))
  }

  async function handleSaveClick() {
    const diff = buildPropertyDiff(editableGroups, draft)
    if (Object.keys(diff).length === 0) {
      setEditing(false)
      return
    }
    setSaving(true)
    setSaveErrors(null)
    const result = await updateItem(item.itemId, diff)
    setSaving(false)
    if (result.ok) {
      setEditing(false)
      onSaved?.()
    } else {
      setSaveErrors(result.errors)
    }
  }

  // Properties/Links/States/Markers used to be always-visible, independently collapsible/plain
  // sections; a tab view reads better now that each has real content worth its own screen space --
  // only one needs to be visible at a time. States is omitted entirely (not just shown empty) for
  // an item type with no state machine defined -- unlike Links/Markers, which can always gain a
  // link/marker later, this item can never have states, so the tab would be permanently, not just
  // currently, empty. Preview is the same kind of omission, and -- unlike every other tab -- goes
  // first when present, since it's the thing most worth seeing immediately for a file whose content
  // a browser can actually render. Properties itself is the flattened dot-path/grid layout
  // (ItemPanePropertyGroupsView) rather than PropertyTreeView's indented tree -- an item-pane-only
  // experiment for now; search results still use the indented tree. While editing, that tab swaps to
  // ItemPanePropertyGroupsEditView, fed editableGroups (schema-driven -- every property the type
  // defines, not just the ones groupedProperties' data-only walk happens to find set) instead.
  const sections = [
    ...(canPreview ? [{ key: 'preview', label: 'Preview', view: <ItemPanePreview url={fileContent.url} />, padding: 0 }] : []),
    {
      key: 'properties',
      label: 'Properties',
      view: editing ? (
        <ItemPanePropertyGroupsEditView groups={editableGroups} draft={draft} onChange={handleFieldChange} />
      ) : (
        <ItemPanePropertyGroupsView groups={groupedProperties} />
      ),
    },
    { key: 'links', label: 'Links', view: <ItemPaneLinks linkEntries={linkEntries} /> },
    ...(getHasStateMachines(item.itemType)
      ? [{ key: 'states', label: 'States', view: <ItemPaneStates stateEntries={stateEntries} /> }]
      : []),
    { key: 'markers', label: 'Markers', view: <ItemPaneMarkers markers={item.markers} /> },
  ]

  return (
    <item-pane-view>
      {/* Edit/Save+Cancel and Delete sit in their own fixed left/right slots (not a single flex row
          with space-between) so one appearing without the other -- e.g. a marker grants delete but
          not edit -- doesn't pull the remaining button to the wrong side. */}
      <item-pane-actions>
        <div className="item-pane-actions-left">
          {!editing && item.permissions?.edit && (
            <button type="button" className="item-pane-action-btn" title="Edit" onClick={handleEditClick}>
              <span aria-hidden="true">✎</span> Edit
            </button>
          )}
          {editing && (
            <>
              <button type="button" className="item-pane-action-btn" title="Save" onClick={handleSaveClick} disabled={saving}>
                <span aria-hidden="true">💾</span> Save
              </button>
              <button type="button" className="item-pane-action-btn" title="Cancel" onClick={handleCancelClick} disabled={saving}>
                <span aria-hidden="true">✕</span> Cancel
              </button>
            </>
          )}
        </div>
        <div className="item-pane-actions-right">
          {item.permissions?.delete && (
            <button
              type="button"
              className="item-pane-action-btn item-pane-action-delete"
              title="Delete"
              disabled={editing}
              onClick={() => setConfirmingDelete(true)}
            >
              <span aria-hidden="true">🗑</span> Delete
            </button>
          )}
        </div>
      </item-pane-actions>

      {saveErrors && (
        <div className="item-pane-save-errors">
          {saveErrors.map((error) => (
            <div key={error.path}>{error.message}</div>
          ))}
        </div>
      )}

      <TabView sections={sections} activeKey={activeTabKey} onActiveKeyChange={setActiveTabKey} borderStyle="top" />

      <ModalPopupView
        open={confirmingDelete}
        message={`Delete "${item.displayLabel}"? This can't be undone.`}
        onConfirm={handleConfirmDelete}
        onCancel={() => setConfirmingDelete(false)}
      />
    </item-pane-view>
  )
}

function ItemPaneLinks({ linkEntries }) {
  if (linkEntries.length === 0) {
    return <div className="item-pane-empty">No links.</div>
  }

  return (
    <>
      {linkEntries.map(([perspective, links]) => (
        <div key={perspective} className="item-pane-link-group">
          <span className="item-pane-link-perspective">{perspective}</span>
          {links.map((link) => (
            <div key={link.linkId} className="item-pane-link-target">
              <div className="item-pane-link-target-label">{link.item.displayLabel}</div>
              {flattenProperties(link.item.properties).map(([path, value]) => (
                <div key={path} className="item-pane-property">
                  <span className="item-pane-property-name">{path}</span>
                  <span className="item-pane-property-value">{formatLinkedPropertyValue(value)}</span>
                </div>
              ))}
            </div>
          ))}
        </div>
      ))}
    </>
  )
}

// A linked item's property can itself be a {value,label} controlled-list leaf, or (SET/LIST) an
// array of them -- flattenProperties never descends into either shape (see its own comment), so
// this is the one place that resolves it down to display text.
function formatLinkedPropertyValue(value) {
  if (Array.isArray(value)) return value.map((v) => controlledListDisplayValue(v)).join(', ')
  return String(controlledListDisplayValue(value))
}

// <img> covers everything a browser can actually render inline this way -- SVG included, since
// browsers rasterize it the same as any other image src. Video/audio aren't image-displayable the
// same way (they'd need their own element and controls, a different preview altogether), so
// they're deliberately not included here even though some are technically "browser-displayable."
function isPreviewableMimeType(mimeType) {
  return typeof mimeType === 'string' && mimeType.startsWith('image/')
}

function ItemPanePreview({ url }) {
  return <img className="item-pane-preview-image" src={url} alt="" />
}

function ItemPaneStates({ stateEntries }) {
  if (stateEntries.length === 0) {
    return <div className="item-pane-empty">No states.</div>
  }

  return (
    <>
      {stateEntries.map(([machineName, state]) => (
        <div key={machineName} className="item-pane-state">
          <span className="item-pane-state-machine">{machineName}</span>
          <span className="item-pane-state-value">{state.currentState ?? '—'}</span>
        </div>
      ))}
    </>
  )
}

function ItemPaneMarkers({ markers }) {
  if (!markers || markers.length === 0) {
    return <div className="item-pane-empty">No markers.</div>
  }

  return <div className="item-pane-markers">{markers.join(', ')}</div>
}

export default ItemPaneView
