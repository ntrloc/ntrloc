import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SchemaEditor } from './schema-editor';

describe('SchemaEditor', () => {
  let component: SchemaEditor;
  let fixture: ComponentFixture<SchemaEditor>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SchemaEditor],
    }).compileComponents();

    fixture = TestBed.createComponent(SchemaEditor);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
