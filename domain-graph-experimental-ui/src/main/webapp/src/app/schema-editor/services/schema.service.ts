import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AdminSchema, Schema } from '../model/schema.model';

@Injectable({ providedIn: 'root' })
export class SchemaService {

  private readonly apiUrl = '/api/schema';

  constructor(private http: HttpClient) {}

  getSchema(): Observable<Schema> {
    return this.http.get<Schema>(this.apiUrl);
  }

  getAdminSchema(): Observable<AdminSchema> {
    return this.http.get<AdminSchema>(`${this.apiUrl}/admin`);
  }

}
