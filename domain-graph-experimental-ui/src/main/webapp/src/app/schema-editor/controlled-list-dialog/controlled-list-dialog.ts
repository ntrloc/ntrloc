import { Component, Inject, OnInit, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatIcon } from '@angular/material/icon';
import { MatIconButton } from '@angular/material/button';
import { ControlledListEntry } from '../model/schema.model';
import { PropertyDefinitionViewModel } from '../model/schema.viewmodel';

interface ControlledListResponse {
  listId: string;
  name: string;
  valueType: string;
  values: Array<{ value: string; label: string | null }>;
}

export interface ControlledListDialogData {
  prop: PropertyDefinitionViewModel;
  pendingValues: ControlledListEntry[] | null;
}

@Component({
  selector: 'app-controlled-list-dialog',
  imports: [NgFor, NgIf, FormsModule, MatButtonModule, MatDialogModule, MatIcon, MatIconButton],
  templateUrl: './controlled-list-dialog.html',
  styleUrl: './controlled-list-dialog.scss',
})
export class ControlledListDialog implements OnInit {
  listName = signal('');
  values = signal<ControlledListEntry[]>([]);
  loading = signal(true);
  newValue = '';
  newLabel = '';

  constructor(
    private http: HttpClient,
    private dialogRef: MatDialogRef<ControlledListDialog>,
    @Inject(MAT_DIALOG_DATA) public data: ControlledListDialogData,
  ) {}

  ngOnInit(): void {
    if (this.data.pendingValues !== null) {
      this.values.set(this.data.pendingValues.map(e => ({ ...e })));
      this.listName.set(this.data.prop.name);
      this.loading.set(false);
    } else {
      this.http.get<ControlledListResponse>(`/api/admin/schema/properties/${this.data.prop.id}/controlled-list`)
        .subscribe({
          next: resp => {
            this.listName.set(resp.name);
            this.values.set(resp.values.map(v => ({ value: String(v.value), label: v.label })));
            this.loading.set(false);
          },
          error: () => this.loading.set(false),
        });
    }
  }

  addValue(): void {
    const v = this.newValue.trim();
    if (!v) return;
    this.values.update(entries => [...entries, { value: v, label: this.newLabel.trim() || null }]);
    this.newValue = '';
    this.newLabel = '';
  }

  removeValue(index: number): void {
    this.values.update(entries => entries.filter((_, i) => i !== index));
  }

  save(): void {
    this.dialogRef.close(this.values());
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
