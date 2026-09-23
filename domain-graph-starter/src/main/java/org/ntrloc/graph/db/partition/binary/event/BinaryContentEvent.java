package org.ntrloc.graph.db.partition.binary.event;

import java.util.UUID;

// Published by BinaryPartitionManagerImpl whenever a binary_content row is genuinely inserted for
// the first time -- never on a dedup hit (two uploads of identical bytes only ever publish this
// once, since Postgres's row lock during the upsert only lets one of them observe the fresh insert).
// Deliberately bound to the content, not to any item that later references it: extraction is a fact
// about the bytes, computed once per sha256/md5/length regardless of how many items point at them.
public sealed interface BinaryContentEvent {

    record Created(UUID binaryContentId) implements BinaryContentEvent {}
}
