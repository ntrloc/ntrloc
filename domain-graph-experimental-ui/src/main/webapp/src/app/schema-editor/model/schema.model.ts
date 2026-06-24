export type PropertyType = 'STRING' | 'INT' | 'LONG' | 'DATE' | 'DATETIME' | 'BOOLEAN' | 'BINARY' | 'OBJECT';
export type PropertyCardinality = 'SINGLE' | 'LIST' | 'SET';
export type PropertyRequirement = 'OPTIONAL' | 'REQUIRED' | 'DEPRECATED';

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
  requirement: PropertyRequirement;
}

export interface AdminItemLinkPerspective {
  itemType: string;
  description: string | null;
  minCardinality: number;
  maxCardinality: number | null;
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
  propertyTypes: PropertyTypeInfo[];
}
