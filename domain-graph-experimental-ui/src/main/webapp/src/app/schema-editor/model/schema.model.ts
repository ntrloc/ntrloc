export type PropertyType = 'STRING' | 'INT' | 'LONG' | 'DATE' | 'DATETIME' | 'BOOLEAN' | 'BINARY' | 'OBJECT';
export type PropertyCardinality = 'SINGLE' | 'LIST' | 'SET';
export type PropertyUsage = 'OPTIONAL' | 'REQUIRED' | 'DEPRECATED';

export interface PropertyTypeInfo {
  type: PropertyType;
  validCardinalities: PropertyCardinality[];
}

export interface DefinedIn {
  entityType: 'item' | 'trait';
  entityName: string;
}

// --- Calculated (user-facing) schema ---

export interface PropertyDefinition {
  id: string;
  name: string;
  description: string | null;
  type: PropertyType;
  cardinality: PropertyCardinality;
  definedIn: DefinedIn | null;
}

export interface TargetEntity {
  name: string;
  kind: 'item' | 'trait';
}

export interface ItemLinkPerspective {
  targets: TargetEntity[];
  description: string | null;
  minCardinality: number;
  maxCardinality: number | null;
  properties: PropertyDefinition[] | null;
  definedIn: DefinedIn | null;
}

export interface ItemDefinition {
  id: string;
  name: string;
  description: string | null;
  properties: PropertyDefinition[] | null;
  links: Record<string, ItemLinkPerspective[]> | null;
}

export interface Schema {
  items: ItemDefinition[];
}

// --- Admin schema ---

export interface ControlledListEntry {
  value: string;
  label: string | null;
}

export interface PropertyGroup {
  id: string;
  name: string;
}

export interface AdminPropertyDefinition {
  id: string;
  name: string;
  description: string | null;
  type: PropertyType;
  cardinality: PropertyCardinality;
  usage: PropertyUsage;
  definedIn: DefinedIn | null;
  controlledListId: string | null;
  groupId: string | null;
}

export interface AdminItemLinkPerspective {
  id: string;
  linkId: string;
  targets: TargetEntity[];
  description: string | null;
  minCardinality: number;
  maxCardinality: number | null;
  definedIn: DefinedIn | null;
}

export interface AdminLink {
  id: string;
  properties: AdminPropertyDefinition[] | null;
}

export interface SortableField {
  name: string;
  system: boolean;
}

export interface TraitRef {
  id: string;
  name: string;
}

export interface AdminItemDefinition {
  id: string;
  entityId: string;
  name: string;
  description: string | null;
  traits: TraitRef[];
  properties: AdminPropertyDefinition[] | null;
  links: Record<string, AdminItemLinkPerspective[]> | null;
  sortableFields: SortableField[];
  groups: PropertyGroup[];
}

export interface AdminTraitDefinition {
  id: string;
  name: string;
  description: string | null;
  properties: AdminPropertyDefinition[] | null;
  links: Record<string, AdminItemLinkPerspective[]> | null;
  sortableFields: SortableField[];
}

export interface AdminSchema {
  items: AdminItemDefinition[];
  traits: AdminTraitDefinition[];
  links: AdminLink[];
  propertyTypes: PropertyTypeInfo[];
}

// --- Definition mutations ---

interface InlineProperty {
  name: string;
  description: string | null;
  propertyType: PropertyType;
  cardinality: PropertyCardinality;
  usage: PropertyUsage;
}

export type DefinitionMutation =
  | { type: 'CREATE_ITEM'; name: string; description: string | null; properties: InlineProperty[] }
  | { type: 'CREATE_TRAIT'; name: string; description: string | null; properties: InlineProperty[] }
  | { type: 'IMPLEMENT_TRAIT'; itemId: string; traitId: string }
  | { type: 'REMOVE_TRAIT'; itemId: string; traitId: string }
  | { type: 'UPDATE_ITEM'; id: string; name: string; description: string | null }
  | { type: 'CREATE_ITEM_PROPERTY'; itemId: string; name: string; description: string | null; propertyType: PropertyType; cardinality: PropertyCardinality; usage: PropertyUsage }
  | { type: 'CREATE_LINK_PROPERTY'; linkId: string; name: string; description: string | null; propertyType: PropertyType; cardinality: PropertyCardinality; usage: PropertyUsage }
  | { type: 'UPDATE_PROPERTY'; id: string; name: string; description: string | null; propertyType: PropertyType; cardinality: PropertyCardinality; usage: PropertyUsage }
  | { type: 'DELETE_PROPERTY'; id: string }
  | { type: 'UPDATE_PERSPECTIVE'; id: string; name: string | null; description: string | null; minCardinality: number; maxCardinality: number | null }
  | { type: 'REPLACE_CONTROLLED_LIST'; propertyId: string; values: ControlledListEntry[] }
  | { type: 'CREATE_PROPERTY_GROUP'; entityId: string; name: string }
  | { type: 'UPDATE_PROPERTY_GROUP'; id: string; name: string }
  | { type: 'DELETE_PROPERTY_GROUP'; id: string }
  | { type: 'ASSIGN_ITEM_PROPERTY_GROUP'; itemId: string; propertyId: string; groupId: string | null };
