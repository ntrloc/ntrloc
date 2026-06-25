import { Injectable } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { SchemaService } from './services/schema.service';
import { AdminSchema } from './model/schema.model';

@Injectable({ providedIn: 'root' })
export class SchemaModel {
  private _schema: AdminSchema | null = null;

  constructor(private delegate: SchemaService) {}

  get schema(): AdminSchema | null {
    return this._schema;
  }

  load(): Observable<AdminSchema> {
    if (this._schema) return of(this._schema);
    return this.delegate.getAdminSchema().pipe(
      tap(schema => this._schema = schema)
    );
  }
}
