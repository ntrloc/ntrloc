import { Component, OnInit } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ItemDefinitionViewModel } from './model/schema.viewmodel';
import { SchemaViewModel } from './schema-view-model';
import { SchemaService } from './services/schema.service';
import { ItemList } from './item-list/item-list';
import { ItemDetail } from './item-detail/item-detail';
import { SaveConfirmDialog } from './save-confirm-dialog/save-confirm-dialog';

@Component({
  selector: 'app-schema-editor',
  imports: [ItemList, ItemDetail, MatButtonModule],
  templateUrl: './schema-editor.html',
  styleUrl: './schema-editor.scss',
})
export class SchemaEditor implements OnInit {
  constructor(
    readonly schemaViewModel: SchemaViewModel,
    private schemaService: SchemaService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.schemaViewModel.load();
  }

  onItemSelected(item: ItemDefinitionViewModel): void {
    this.schemaViewModel.selectedItem.set(item);
  }

  onNewItem(): void {
    // TODO
  }

  onSave(): void {
    const summary = this.schemaViewModel.describePendingChanges();
    const operations = this.schemaViewModel.collectOperations();
    const ref = this.dialog.open(SaveConfirmDialog, {
      data: { summary },
      width: '480px',
    });
    ref.afterClosed().subscribe(confirmed => {
      if (confirmed) {
        this.schemaService.applyOperations(operations).subscribe({
          next: () => this.schemaViewModel.reload(),
          error: (err) => console.error('[save] error:', err),
        });
      }
    });
  }
}
