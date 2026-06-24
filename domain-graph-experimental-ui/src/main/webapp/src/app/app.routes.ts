import { Routes } from '@angular/router';
import { MainContent } from './main-content/main-content';
import { SchemaEditor } from './schema-editor/schema-editor';
import { UserEditor } from './user-editor/user-editor';

export const routes: Routes = [
  {
    path: '',
    component: MainContent,
    children: [
      { path: 'schema', component: SchemaEditor },
      { path: 'users', component: UserEditor },
      { path: '', redirectTo: 'schema', pathMatch: 'full' }
    ]
  }
];
