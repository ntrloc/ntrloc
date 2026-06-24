import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import { PropertyTypeInfo, Schema } from '../model/schema.model';

@Injectable({ providedIn: 'root' })
export class SchemaService {

  private readonly apiUrl = '/api/schema';

  private propertyTypes$: Observable<PropertyTypeInfo[]> | null = null;

  constructor(private http: HttpClient) {}

  getSchema(): Observable<Schema> {
    return this.http.get<Schema>(this.apiUrl);
  }

  getPropertyTypes(): Observable<PropertyTypeInfo[]> {
    if (!this.propertyTypes$) {
      this.propertyTypes$ = this.http
        .get<PropertyTypeInfo[]>(`${this.apiUrl}/property-types`)
        .pipe(shareReplay(1));
    }
    return this.propertyTypes$;
  }
}
