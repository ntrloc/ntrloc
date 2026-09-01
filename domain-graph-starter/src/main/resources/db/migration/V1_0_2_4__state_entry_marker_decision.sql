-- A NORMAL state may declare a DMN decision (by key, deployed independently like entry_process_id)
-- that runs on entry: it looks at the item's property values and returns marker names to apply for
-- as long as the item is in that state. Nullable; pseudostates never get one.
ALTER TABLE schema_state ADD COLUMN entry_marker_decision_key TEXT;
