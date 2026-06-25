import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { NgFor } from '@angular/common';
import { PendingChangeSummary } from '../schema-view-model';

@Component({
  selector: 'app-save-confirm-dialog',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule, NgFor],
  templateUrl: './save-confirm-dialog.html',
  styleUrl: './save-confirm-dialog.scss',
})
export class SaveConfirmDialog {
  constructor(
    public dialogRef: MatDialogRef<SaveConfirmDialog>,
    @Inject(MAT_DIALOG_DATA) public data: { summary: PendingChangeSummary[] }
  ) {}

  confirm(): void { this.dialogRef.close(true); }
  cancel(): void { this.dialogRef.close(false); }
}
