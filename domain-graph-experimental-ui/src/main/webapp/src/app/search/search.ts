import { Component } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { DragDropModule, CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { SearchPane, WindowState } from './search-pane/search-pane';

interface PaneEntry {
  id: number;
  windowState: WindowState;
}

@Component({
  selector: 'app-search',
  imports: [NgFor, NgIf, MatButton, MatIcon, DragDropModule, SearchPane],
  templateUrl: './search.html',
  styleUrl: './search.scss',
})
export class Search {
  panes: PaneEntry[] = [];
  private nextId = 1;

  constructor() {
    this.addPane();
  }

  get activePanes(): PaneEntry[] {
    return this.panes.filter(p => p.windowState !== 'minimized');
  }

  get minimizedPanes(): PaneEntry[] {
    return this.panes.filter(p => p.windowState === 'minimized');
  }

  get gridCols(): number {
    return this.activePanes.length <= 1 ? 1 : 2;
  }

  get hasMaximized(): boolean {
    return this.panes.some(p => p.windowState === 'maximized');
  }

  addPane(): void {
    this.panes.push({ id: this.nextId++, windowState: 'normal' });
  }

  closePane(id: number): void {
    this.panes = this.panes.filter(p => p.id !== id);
  }

  maximizePane(id: number): void {
    this.panes.forEach(p => {
      p.windowState = p.id === id ? 'maximized' : 'normal';
    });
  }

  minimizePane(id: number): void {
    const pane = this.panes.find(p => p.id === id);
    if (pane) pane.windowState = 'minimized';
  }

  restorePane(id: number): void {
    const pane = this.panes.find(p => p.id === id);
    if (pane) pane.windowState = 'normal';
  }

  onDrop(event: CdkDragDrop<PaneEntry[]>): void {
    const moved = this.activePanes[event.previousIndex];
    const target = this.activePanes[event.currentIndex];
    moveItemInArray(this.panes, this.panes.indexOf(moved), this.panes.indexOf(target));
  }
}
