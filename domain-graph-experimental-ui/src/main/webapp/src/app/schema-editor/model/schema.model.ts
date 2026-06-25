export type PropertyType = 'STRING' | 'INT' | 'LONG' | 'DATE' | 'DATETIME' | 'BOOLEAN' | 'BINARY' | 'OBJECT';
export type PropertyCardinality = 'SINGLE' | 'LIST' | 'SET';
export type PropertyUsage = 'OPTIONAL' | 'REQUIRED' | 'DEPRECATED';

export interface PropertyTypeInfo {
  type: PropertyType;
  validCardinalities: PropertyCardinality[];
}

// --- Calculated (user-facing) schema ---

export interface PropertyDefinition {
  id: string;
  name: string;
  description: string | null;
  type: PropertyType;
  cardinality: PropertyCardinality;
}

export interface ItemLinkPerspective {
  itemType: string;
  description: string | null;
  minCardinality: number;
  maxCardinality: number | null;
  properties: PropertyDefinition[] | null;
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

export interface AdminPropertyDefinition {
  id: string;
  name: string;
  description: string | null;
  type: PropertyType;
  cardinality: PropertyCardinality;
  usage: PropertyUsage;
}

export interface AdminItemLinkPerspective {
  id: string;
  linkId: string;
  itemType: string;
  description: string | null;
  minCardinality: number;
  maxCardinality: number | null;
}

export interface AdminLink {
  id: string;
  properties: AdminPropertyDefinition[] | null;
}

export interface AdminItemDefinition {
  id: string;
  name: string;
  description: string | null;
  properties: AdminPropertyDefinition[] | null;
  links: Record<string, AdminItemLinkPerspective[]> | null;
}

export interface AdminSchema {
  items: AdminItemDefinition[];
  links: AdminLink[];
  propertyTypes: PropertyTypeInfo[];
}

// --- Definition mutations ---

export type DefinitionMutation =
  | { type: 'UPDATE_ITEM'; id: string; name: string; description: string | null }
  | { type: 'CREATE_ITEM_PROPERTY'; itemId: string; name: string; description: string | null; propertyType: PropertyType; cardinality: PropertyCardinality; usage: PropertyUsage }
  | { type: 'CREATE_LINK_PROPERTY'; linkId: string; name: string; description: string | null; propertyType: PropertyType; cardinality: PropertyCardinality; usage: PropertyUsage }
  | { type: 'UPDATE_PROPERTY'; id: string; name: string; description: string | null; propertyType: PropertyType; cardinality: PropertyCardinality; usage: PropertyUsage }
  | { type: 'DELETE_PROPERTY'; id: string }
  | { type: 'UPDATE_PERSPECTIVE'; id: string; name: string | null; description: string | null; minCardinality: number; maxCardinality: number | null };
