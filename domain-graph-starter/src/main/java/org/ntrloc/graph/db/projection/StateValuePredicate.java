package org.ntrloc.graph.db.projection;

// Both names are resolved to the schema's stable ids at query time (RegisterPartitionManager.
// translatePredicate) -- same rule as PropertyValuePredicate's propertyName: a state machine or
// state can be renamed without breaking a saved/reused predicate.
public record StateValuePredicate(String stateMachineName, String stateName) implements Predicate {}
