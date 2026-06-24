import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgIf } from '@angular/common';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { CdkDragHandle } from '@angular/cdk/drag-drop';

export type WindowState = 'normal' | 'maximized' | 'minimized';

@Component({
  selector: 'app-search-pane',
  imports: [NgIf, MatIconButton, MatIcon, CdkDragHandle],
  templateUrl: './search-pane.html',
  styleUrl: './search-pane.scss',
})
export class SearchPane {
  @Input() paneId = 0;
  @Input() windowState: WindowState = 'normal';

  @Output() closePane = new EventEmitter<void>();
  @Output() maximizePane = new EventEmitter<void>();
  @Output() minimizePane = new EventEmitter<void>();
  @Output() restorePane = new EventEmitter<void>();
}
