# ntrloc Provenance & Liability Requirements

Parked here so it doesn't get lost while the marker/signal decision-mechanism redesign continues
elsewhere -- this isn't a spec to build against yet, it's a requirement to keep in view so that
redesign doesn't end up needing to be retrofitted later.

## Why this exists

While researching what forming an LLC and selling ntrloc commercially would actually require,
liability insurance and master service agreements came up -- and with them, the realization that
defensible, auditable "change provenance" isn't a nice-to-have diagnostic feature. For a vendor, it's
closer to a precondition for being able to defend yourself when something goes wrong with a
customer's data.

## The grounding incident

Not an ntrloc incident -- a real one from Scholastic (2016, recounted directly from experience).
A cover for an embargoed product -- one Scholastic didn't even own the rights to -- escaped the DAM
system because a user made a mistake, got leaked publicly (it surfaced on Twitter), and created a
serious legal situation. How that resolved for Scholastic isn't known, but critically: the DAM
vendor's own internal auditing was thorough enough that any attempt by Scholastic to pin the fault on
the vendor's software would have been shut down immediately -- the vendor could show definitively
that a human action, not a defect in their system, caused the leak.

Directly relevant to ntrloc's own design direction: this is exactly the failure mode automated,
system-derived access markers (as opposed to manually-applied, human-remembered permissions) are
meant to eliminate. It's also a preview of the exposure ntrloc itself would carry as a vendor, not
just a benefit it offers customers.

## The three-scenario taxonomy

Worked out against a concrete "embargo escape" scenario (a Cover linked to a Product; the Product
carries `embargoed` and `owner` properties; the Cover's access markers are meant to derive from
them):

1. **Wrong fact.** A cover was linked to a product that should have been embargoed, but wasn't.
   Fault: whoever set `embargoed` incorrectly. Needs: ordinary **data provenance** -- who set which
   property, when. Already substantially covered by the existing append-only ledger, which already
   attributes every mutation to a principal and timestamp.

2. **Wrong decision, correct fact.** The product was correctly embargoed, but the cover remained
   visible to everyone anyway. Fault: whoever authored/configured the marker-assignment rule. Needs:
   **decision-logic provenance** -- not what data a decision saw, but what the decision's own logic
   *said*, and who wrote it that way, at the exact moment it ran. Partially covered today
   (`RuleAppliedMarker`/`StateAppliedMarker` already record a `ruleVersion`/`decisionVersion`), but
   this needs to be hardened into a full, queryable authored-change-history for decision logic
   itself -- parallel to, but distinct from, the data-change ledger.

3. **Correct everything, still escaped.** Correct fact, correct marker, correct grants -- the content
   still got out. Fault is unclear from the system's own perspective; most likely a legitimately
   permitted person did something with it outside the system's boundary. Needs: **access/read
   provenance** -- a record of who actually viewed or downloaded the binary content, not just who
   mutated anything. **This does not exist in ntrloc today, in any form.**

## The actual ask: one unified timeline, not three logs

A real forensic or legal inquiry won't want three separate systems to cross-reference by hand. It
wants one assembled, chronological answer to "show me everything that happened to this item, from
creation to now" -- every property set and by whom; every marker decision that fired, which rule/
version produced it, and who authored that version; every view; every download -- interleaved into
one readable timeline.

That means all three source capabilities need to be:
- Queryable by item.
- Mutually correlatable -- a marker-application record needs to carry enough to join back to the
  exact decision version and that version's own authorship history, not just "rule X fired."

## Current state (as of 2026-10-01)

- **Data provenance**: largely covered by the existing ledger.
- **Decision-logic provenance**: partial. `ruleVersion`/`decisionVersion` already exist on marker
  attributions (`RuleAppliedMarker`, `StateAppliedMarker`), but there's no confirmed, full
  authored-history of decision-logic changes themselves (who edited a rule/decision table, when,
  what it said before and after) exposed as a queryable trail.
- **Access/read provenance**: does not exist at all. No logging today of who viewed or downloaded an
  item's binary content.
- No unified cross-source timeline view exists.

## How to apply

- This should **inform** the in-progress marker/signal decision-mechanism redesign even though
  building the full timeline feature itself is explicitly deferred: whatever provenance the new
  decision mechanism emits needs to be detailed and correlatable enough to feed this future timeline
  (which decision, which version, which input facts, at minimum) -- don't build something that would
  need retrofitting later to support this.
- Building the actual timeline UI/report, and the access/download logging mechanism it depends on,
  is separate, not-yet-scoped future work.

## Open questions

- Can the existing ledger support efficient point-in-time reconstruction of "what were an item's
  (and any linked item's) merged facts at moment T," not just "what changed at moment T"? This
  matters for proving a decision saw correct data, not just that the right decision version ran.
- What retention/performance tradeoffs come with logging every read/download? Not addressed at all
  yet.
- Should decision-logic edit history live in the same ledger mechanism as data changes, or a
  separate schema-change history?
