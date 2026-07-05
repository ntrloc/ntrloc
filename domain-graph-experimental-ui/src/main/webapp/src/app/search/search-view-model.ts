import { computed, Injectable, signal } from '@angular/core';
import { CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { SchemaService } from '../schema-editor/services/schema.service';
import { ProjectionService } from './services/projection.service';
import { SearchPaneViewModel } from './search-pane-view-model';

export type WindowState = 'normal' | 'maximized' | 'minimized';

interface SearchPaneEntry {
  id: number;
  windowState: WindowState;
  vm: SearchPaneViewModel;
}

@Injectable({ providedIn: 'root' })
export class SearchViewModel {

  private readonly _panes = signal<SearchPaneEntry[]>([]);
  private nextId = 1;

  readonly panes = this._panes.asReadonly();
  readonly activePanes = computed(() => this._panes().filter(p => p.windowState !== 'minimized'));
  readonly minimizedPanes = computed(() => this._panes().filter(p => p.windowState === 'minimized'));
  readonly gridCols = computed(() => this.activePanes().length <= 1 ? 1 : 2);
  readonly hasMaximized = computed(() => this._panes().some(p => p.windowState === 'maximized'));

  constructor(
    private readonly projectionService: ProjectionService,
    private readonly schemaService: SchemaService
  ) {
    this.addPane();
  }

  getPaneVm(id: number): SearchPaneViewModel | undefined {
    return this._panes().find(p => p.id === id)?.vm;
  }

  addPane(): void {
    const id = this.nextId++;
    const vm = new SearchPaneViewModel(id, this.projectionService, this.schemaService);
    this._panes.update(panes => [...panes, { id, windowState: 'normal', vm }]);
  }

  closePane(id: number): void {
    this._panes.update(panes => panes.filter(p => p.id !== id));
  }

  maximizePane(id: number): void {
    this._panes.update(panes => panes.map(p => ({
      ...p,
      windowState: (p.id === id ? 'maximized' : 'normal') as WindowState
    })));
  }

  minimizePane(id: number): void {
    this._panes.update(panes => panes.map(p =>
      p.id === id ? { ...p, windowState: 'minimized' as WindowState } : p
    ));
  }

  restorePane(id: number): void {
    this._panes.update(panes => panes.map(p =>
      p.id === id ? { ...p, windowState: 'normal' as WindowState } : p
    ));
  }

  onDrop(event: CdkDragDrop<SearchPaneEntry[]>): void {
    const active = this.activePanes();
    const moved = active[event.previousIndex];
    const target = active[event.currentIndex];
    this._panes.update(all => {
      const copy = [...all];
      moveItemInArray(copy, copy.indexOf(moved), copy.indexOf(target));
      return copy;
    });
  }
}
