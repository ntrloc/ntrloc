// The "Model" -- caches the raw AdminSchema fetched from the server. Does not track dirty state
// or pending mutations; that's schema-view-model.js's job. Mirrors the Angular reference's
// SchemaModel exactly (schema-editor/schema-model.ts).
const schemaModel = {
  _schema: null,

  load() {
    if (this._schema) return Promise.resolve(this._schema);
    return schemaService.getAdminSchema().then((schema) => {
      this._schema = schema;
      return schema;
    });
  },

  reload() {
    this._schema = null;
    return this.load();
  },
};
