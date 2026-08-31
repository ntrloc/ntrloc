-- START / END pseudostates for state machines, replacing schema_state.is_initial.
--
-- Every machine now owns exactly one START and one END state (kind = 'START' / 'END'), created with
-- the machine and undeletable. NORMAL states are the user-defined ones. START has at most one
-- outgoing transition (to a NORMAL state, no guard); END has no outgoing transitions. Pseudostate
-- rows carry a sentinel name ('__start__' / '__end__'); the editor renders them by kind, not name.
--
-- schema_item.init_process_id (and its SET_INIT_PROCESS mutation) is dropped -- it had no runtime
-- consumer and dynamic initial-state selection is gone.

ALTER TABLE schema_state ADD COLUMN kind TEXT NOT NULL DEFAULT 'NORMAL'
    CHECK (kind IN ('NORMAL', 'START', 'END'));

-- One START + one END per existing machine.
INSERT INTO schema_state (state_machine_id, name, kind)
SELECT id, '__start__', 'START' FROM schema_state_machine;

INSERT INTO schema_state (state_machine_id, name, kind)
SELECT id, '__end__', 'END' FROM schema_state_machine;

-- Preserve intent: where a machine had exactly one is_initial NORMAL state, wire START -> it.
-- (Postgres has no min(uuid); the count(*) = 1 guard means the join yields exactly one row.)
INSERT INTO schema_state_transition (from_state_id, to_state_id, name)
SELECT s_start.id, init.id, 'start'
FROM schema_state s_start
JOIN schema_state init
    ON init.state_machine_id = s_start.state_machine_id
   AND init.is_initial = TRUE
   AND init.kind = 'NORMAL'
WHERE s_start.kind = 'START'
  AND (SELECT count(*) FROM schema_state x
       WHERE x.state_machine_id = s_start.state_machine_id
         AND x.is_initial = TRUE AND x.kind = 'NORMAL') = 1;

ALTER TABLE schema_state DROP COLUMN is_initial;
ALTER TABLE schema_item  DROP COLUMN init_process_id;
