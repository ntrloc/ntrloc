import { useState } from 'react'
import ChevronIcon from '../../../controls/chevron-icon/ChevronIcon'
import PropertyTreeView from '../PropertyTreeView'
import { useLinkModel } from '../../../global/model/LinkModel'
import './ItemTypeLinkView.css'

// max unset means "any" -- nothing meaningful to show alongside min then except the range itself.
// A finite max is shown alone (dropping min) -- e.g. (0, 1) reads as just "(1)" -- since which of
// optional/required a single-valued link is isn't this diagram's concern yet.
function formatCardinality({ min, max }) {
  return max == null ? `${min}-any` : `${max}`
}

// One link's own diagram -- two boxes (this item type and whatever it targets) with an arrow each
// direction, labeled with that direction's own perspective name and cardinality -- plus, below it
// in the same bordered card, the link's own properties. Reusing PropertyTreeView as-is (the same
// table used for an item type/trait's own Properties tab) is a first pass, not a final call -- a
// full filterable table may turn out heavier than a single link card wants.
function ItemTypeLinkView({ sourceName, link, onSelectTrait }) {
  const { getLinkPropertyTree } = useLinkModel()
  const [propertiesExpanded, setPropertiesExpanded] = useState(true)
  const targetName = link.target?.name ?? 'Unknown'
  const propertyTree = getLinkPropertyTree(link.linkId)

  return (
    <item-type-link-view>
      <div className="item-type-link-diagram">
        <div className="item-type-link-box item-type-link-box-source">{sourceName}</div>
        <div className="item-type-link-arrows">
          <div className="item-type-link-arrow-row">
            <span className="item-type-link-arrow-label">
              {link.name} ({formatCardinality(link.cardinality)})
            </span>
            <div className="item-type-link-arrow item-type-link-arrow-forward" />
          </div>
          {link.reverse && (
            <div className="item-type-link-arrow-row">
              <div className="item-type-link-arrow item-type-link-arrow-backward" />
              <span className="item-type-link-arrow-label">
                {link.reverse.name} ({formatCardinality(link.reverse.cardinality)})
              </span>
            </div>
          )}
        </div>
        <div className="item-type-link-box">{targetName}</div>
      </div>
      <div className="item-type-link-properties">
        <button
          type="button"
          className="item-type-link-properties-toggle"
          onClick={() => setPropertiesExpanded((current) => !current)}
        >
          <ChevronIcon expanded={propertiesExpanded} />
          Properties
        </button>
        {propertiesExpanded && <PropertyTreeView tree={propertyTree} onSelectTrait={onSelectTrait} />}
      </div>
    </item-type-link-view>
  )
}

export default ItemTypeLinkView
