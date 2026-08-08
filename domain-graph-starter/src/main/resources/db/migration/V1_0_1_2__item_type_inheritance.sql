-- Item type inheritance: a nullable, self-referencing supertype forms a single-parent tree
-- across schema_item, plus an abstract flag that blocks direct instantiation of a type meant
-- only to be extended. No cascade on supertype_id -- re-parenting/deleting a supertype is a
-- live, read-tolerant schema edit handled at the application layer (SchemaMutationValidation),
-- not something the DB should enforce via cascade.

ALTER TABLE schema_item ADD COLUMN supertype_id UUID REFERENCES schema_item(id);
ALTER TABLE schema_item ADD COLUMN abstract BOOLEAN NOT NULL DEFAULT false;
