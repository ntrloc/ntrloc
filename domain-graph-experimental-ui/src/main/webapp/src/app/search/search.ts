import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { SearchPane } from './search-pane/search-pane';
import { SearchViewModel } from './search-view-model';

@Component({
  selector: 'app-search',
  imports: [MatButton, MatIcon, DragDropModule, SearchPane],
  templateUrl: './search.html',
  styleUrl: './search.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Search {
  protected readonly vm = inject(SearchViewModel);
}
