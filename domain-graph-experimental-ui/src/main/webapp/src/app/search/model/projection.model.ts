export interface ProjectionSpec {
  itemTypeName: string;
  sortField?: string | null;
  sortDirection?: 'ASC' | 'DESC';
}

export interface ProjectedItem {
  itemId: string;
  itemType: string;
  properties: Record<string, unknown>;
  links: Record<string, ProjectedLink[]>;
}

export interface ProjectedLink {
  linkId: string;
  properties: Record<string, unknown>;
  item: ProjectedItem;
}

export interface ProjectionResult {
  items: ProjectedItem[];
}
