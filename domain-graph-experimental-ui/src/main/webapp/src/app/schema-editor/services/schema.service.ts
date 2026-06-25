import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AdminSchema, Schema } from '../model/schema.model';

@Injectable({ providedIn: 'root' })
export class SchemaService {

  private readonly schemaUrl = '/api/schema';
  private readonly adminSchemaUrl = '/api/admin/schema';

  constructor(private http: HttpClient) {}

  getSchema(): Observable<Schema> {
    return this.http.get<Schema>(this.schemaUrl);
  }

  getAdminSchema(): Observable<AdminSchema> {
    return this.http.get<AdminSchema>(this.adminSchemaUrl);
  }

}
