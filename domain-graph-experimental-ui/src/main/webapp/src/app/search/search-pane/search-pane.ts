import { ChangeDetectionStrategy, Component, EventEmitter, inject, Input, OnInit, Output } from '@angular/core';
import { JsonPipe } from '@angular/common';
import { MatIconButton, MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { CdkDragHandle } from '@angular/cdk/drag-drop';
import { SearchViewModel, WindowState } from '../search-view-model';
import { SearchPaneViewModel } from '../search-pane-view-model';

@Component({
  selector: 'app-search-pane',
  imports: [JsonPipe, MatIconButton, MatButton, MatIcon, CdkDragHandle],
  templateUrl: './search-pane.html',
  styleUrl: './search-pane.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SearchPane implements OnInit {
  @Input() paneId = 0;
  @Input() windowState: WindowState = 'normal';

  @Output() closePane = new EventEmitter<void>();
  @Output() maximizePane = new EventEmitter<void>();
  @Output() minimizePane = new EventEmitter<void>();
  @Output() restorePane = new EventEmitter<void>();

  private searchViewModel = inject(SearchViewModel);
  protected paneVm?: SearchPaneViewModel;

  ngOnInit(): void {
    this.paneVm = this.searchViewModel.getPaneVm(this.paneId);
  }

  protected onTypeSelect(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.paneVm?.selectType(value || null);
  }

  protected onSortFieldSelect(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.paneVm?.selectSortField(value || null);
  }
}
