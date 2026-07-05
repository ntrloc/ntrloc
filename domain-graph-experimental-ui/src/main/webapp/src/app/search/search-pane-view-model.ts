import { computed, signal } from '@angular/core';
import { SchemaService } from '../schema-editor/services/schema.service';
import { SortableField } from '../schema-editor/model/schema.model';
import { ProjectedItem } from './model/projection.model';
import { ProjectionService } from './services/projection.service';
import { ItemTypeSummary, SearchPaneModel } from './search-pane-model';

export class SearchPaneViewModel {

  readonly availableTypes = signal<ItemTypeSummary[]>([]);
  readonly selectedTypeName = signal<string | null>(null);
  readonly sortableFields = signal<SortableField[]>([]);
  readonly selectedSortField = signal<string | null>(null);
  readonly selectedSortDirection = signal<'ASC' | 'DESC'>('ASC');
  readonly results = signal<ProjectedItem[]>([]);
  readonly isLoading = signal(false);
  readonly lastProjectionMs = signal<number | null>(null);

  readonly projectionTimeLabel = computed(() => {
    const ms = this.lastProjectionMs();
    if (ms === null) return null;
    return (ms / 1000).toFixed(3) + 's';
  });

  private readonly model: SearchPaneModel;

  constructor(
    public readonly id: number,
    projectionService: ProjectionService,
    schemaService: SchemaService
  ) {
    this.model = new SearchPaneModel(projectionService, schemaService);
    this.model.fetchItemTypes().subscribe({
      next: types => this.availableTypes.set(types),
      error: () => {}
    });
  }

  selectType(typeName: string | null): void {
    this.selectedTypeName.set(typeName);
    this.results.set([]);
    this.lastProjectionMs.set(null);
    this.selectedSortField.set(null);
    this.selectedSortDirection.set('ASC');
    const type = this.availableTypes().find(t => t.name === typeName);
    this.sortableFields.set(type?.sortableFields ?? []);
  }

  selectSortField(field: string | null): void {
    this.selectedSortField.set(field);
  }

  toggleSortDirection(): void {
    this.selectedSortDirection.update(d => d === 'ASC' ? 'DESC' : 'ASC');
  }

  project(): void {
    const typeName = this.selectedTypeName();
    if (!typeName) return;
    this.isLoading.set(true);
    this.lastProjectionMs.set(null);
    const start = performance.now();
    const sortField = this.selectedSortField();
    this.model.project({
      itemTypeName: typeName,
      sortField: sortField,
      sortDirection: sortField ? this.selectedSortDirection() : undefined
    }).subscribe({
      next: result => {
        this.lastProjectionMs.set(performance.now() - start);
        this.results.set(result.items);
        this.isLoading.set(false);
      },
      error: () => {
        this.isLoading.set(false);
      }
    });
  }
}
