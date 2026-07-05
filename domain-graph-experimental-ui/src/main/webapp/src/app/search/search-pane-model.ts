import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { SchemaService } from '../schema-editor/services/schema.service';
import { SortableField } from '../schema-editor/model/schema.model';
import { ProjectionResult, ProjectionSpec } from './model/projection.model';
import { ProjectionService } from './services/projection.service';

export interface ItemTypeSummary {
  id: string;
  name: string;
  sortableFields: SortableField[];
}

export class SearchPaneModel {

  constructor(
    private readonly projectionService: ProjectionService,
    private readonly schemaService: SchemaService
  ) {}

  fetchItemTypes(): Observable<ItemTypeSummary[]> {
    return this.schemaService.getAdminSchema().pipe(
      map(schema => schema.items.map(item => ({
        id: item.id,
        name: item.name,
        sortableFields: item.sortableFields ?? []
      })))
    );
  }

  project(spec: ProjectionSpec): Observable<ProjectionResult> {
    return this.projectionService.project(spec);
  }
}
