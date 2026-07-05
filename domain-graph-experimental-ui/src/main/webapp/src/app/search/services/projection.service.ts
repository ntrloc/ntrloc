import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ProjectionResult, ProjectionSpec } from '../model/projection.model';

@Injectable({ providedIn: 'root' })
export class ProjectionService {

  private readonly url = '/api/entity/projection';

  constructor(private http: HttpClient) {}

  project(spec: ProjectionSpec): Observable<ProjectionResult> {
    return this.http.post<ProjectionResult>(this.url, spec);
  }
}
