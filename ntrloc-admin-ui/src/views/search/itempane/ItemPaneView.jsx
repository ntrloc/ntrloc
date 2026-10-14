import { useState } from 'react'
import PaneView from '../PaneView'
import PropertyTreeView from '../PropertyTreeView'
import TabView from '../../../controls/tabview/TabView'
import ModalPopupView from '../../../controls/modal/ModalPopupView'
import { flattenProperties } from '../flattenProperties'
import { deleteItem } from '../searchpane/SearchDelegate'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { useItemPaneViewModel } from './ItemPaneViewModel'
import './ItemPaneView.css'

function ItemPaneView({ itemTypeName, itemId, onClose, onItemDeleted, maximized, onToggleMaximize }) {
  const { status, item } = useItemPaneViewModel(itemTypeName, itemId)
  // "Photo: IMG_0988.JPG" -- the item type used to be shown as its own line in the content area;
  // folding it into the header leaves the content area free for just the tabs (see
  // ItemPaneContent) and matches how SearchResultItemView already pairs type + label.
  const title = item ? `${item.itemType}: ${item.displayLabel}` : itemTypeName

  return (
    <PaneView title={title} onClose={onClose} maximized={maximized} onToggleMaximize={onToggleMaximize}>
      {status === 'loading' && <item-pane-view>Loading…</item-pane-view>}
      {status === 'notFound' && <item-pane-view>Item not found.</item-pane-view>}
      {status === 'error' && <item-pane-view>Failed to load item.</item-pane-view>}
      {status === 'ready' && item && <ItemPaneContent item={item} onItemDeleted={onItemDeleted} />}
    </PaneView>
  )
}

function ItemPaneContent({ item, onItemDeleted }) {
  const { getHasStateMachines, getHasTrait } = useItemTypeModel()
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const linkEntries = Object.entries(item.links ?? {})
  const stateEntries = Object.entries(item.states ?? {})

  async function handleConfirmDelete() {
    setConfirmingDelete(false)
    await deleteItem(item.itemId)
    onItemDeleted?.(item.itemId)
  }

  // File is the trait's own fixed namespace/group name, "content" the fixed BINARY property name
  // within it (see ControlledListManager-adjacent schema: every File-trait item type shares this
  // exact shape) -- so this path is safe to hardcode rather than look up, the same way
  // ItemPaneLinks/States already know their own data's shape without a schema lookup.
  const fileContent = item.properties?.File?.content
  const canPreview = getHasTrait(item.itemType, 'File') && Boolean(fileContent?.url) && isPreviewableMimeType(fileContent?.mimeType)

  // Properties/Links/States used to be always-visible, independently collapsible/plain sections; a
  // tab view reads better now that Properties itself is a tree rather than a short flat list (see
  // PropertyTreeView) -- only one of the three needs screen space at a time. Markers stays outside
  // the tabs below -- a short inline summary, not a whole tab's worth of content the way the other
  // three are. States is omitted entirely (not just shown empty) for an item type with no state
  // machine defined -- unlike Links, which can always gain a link later, this item can never have
  // states, so the tab would be permanently, not just currently, empty. Preview is the same kind of
  // omission, and -- unlike every other tab -- goes first when present, since it's the thing most
  // worth seeing immediately for a file whose content a browser can actually render.
  const sections = [
    ...(canPreview ? [{ key: 'preview', label: 'Preview', view: <ItemPanePreview url={fileContent.url} />, padding: 0 }] : []),
    { key: 'properties', label: 'Properties', view: <PropertyTreeView properties={item.properties} /> },
    { key: 'links', label: 'Links', view: <ItemPaneLinks linkEntries={linkEntries} /> },
    ...(getHasStateMachines(item.itemType)
      ? [{ key: 'states', label: 'States', view: <ItemPaneStates stateEntries={stateEntries} /> }]
      : []),
  ]

  return (
    <item-pane-view>
      {/* Edit and Delete sit in their own fixed left/right slots (not a single flex row with
          space-between) so one appearing without the other -- e.g. a marker grants delete but not
          edit -- doesn't pull the remaining button to the wrong side. */}
      <item-pane-actions>
        <div className="item-pane-actions-left">
          {item.permissions?.edit && (
            <button type="button" className="item-pane-action-btn" title="Edit">
              <span aria-hidden="true">✎</span> Edit
            </button>
          )}
        </div>
        <div className="item-pane-actions-right">
          {item.permissions?.delete && (
            <button
              type="button"
              className="item-pane-action-btn item-pane-action-delete"
              title="Delete"
              onClick={() => setConfirmingDelete(true)}
            >
              <span aria-hidden="true">🗑</span> Delete
            </button>
          )}
        </div>
      </item-pane-actions>

      <TabView sections={sections} borderStyle="top" />

      {item.markers && item.markers.length > 0 && (
        <item-pane-section>
          <div className="item-pane-section-title">Markers</div>
          <div className="item-pane-markers">{item.markers.join(', ')}</div>
        </item-pane-section>
      )}

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
                  <span className="item-pane-property-value">{String(value)}</span>
                </div>
              ))}
            </div>
          ))}
        </div>
      ))}
    </>
  )
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

export default ItemPaneView
