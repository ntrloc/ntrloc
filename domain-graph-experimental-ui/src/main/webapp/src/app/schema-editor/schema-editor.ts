import { Component, OnInit } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIcon } from '@angular/material/icon';
import { MatDialog } from '@angular/material/dialog';
import { ItemDefinitionViewModel, TraitDefinitionViewModel } from './model/schema.viewmodel';
import { SchemaViewModel } from './schema-view-model';
import { SchemaService } from './services/schema.service';
import { ItemDetail } from './item-detail/item-detail';
import { SaveConfirmDialog } from './save-confirm-dialog/save-confirm-dialog';

@Component({
  selector: 'app-schema-editor',
  imports: [NgIf, NgFor, ItemDetail, MatButtonModule, MatExpansionModule, MatIcon],
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
    this.schemaViewModel.selectItem(item);
  }

  onTraitSelected(trait: TraitDefinitionViewModel): void {
    this.schemaViewModel.selectTrait(trait);
  }

  onNewItem(): void {
    this.schemaViewModel.newItem();
  }

  onNewTrait(): void {
    this.schemaViewModel.newTrait();
  }

  onSave(): void {
    const summary = this.schemaViewModel.describePendingChanges();
    const mutations = this.schemaViewModel.collectMutations();
    const ref = this.dialog.open(SaveConfirmDialog, {
      data: { summary },
      width: '480px',
    });
    ref.afterClosed().subscribe(confirmed => {
      if (confirmed) {
        this.schemaService.applyMutations(mutations).subscribe({
          next: () => this.schemaViewModel.reload(),
          error: (err) => console.error('[save] error:', err),
        });
      }
    });
  }
}
