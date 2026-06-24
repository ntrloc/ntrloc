export type PropertyType = 'STRING' | 'INT' | 'LONG' | 'DATE' | 'DATETIME' | 'BOOLEAN' | 'BINARY' | 'OBJECT';
export type PropertyCardinality = 'SINGLE' | 'LIST' | 'SET';

export interface PropertyTypeInfo {
  type: PropertyType;
  validCardinalities: PropertyCardinality[];
}

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
