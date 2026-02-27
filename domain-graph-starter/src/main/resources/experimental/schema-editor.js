class SchemaEditor {

    constructor() {
        this.loadFontAwesomeIcons();

        this.stylesId = 'schema-editor-styles';

        if (!document.getElementById(this.stylesId)) {
            this.injectStyles();
        }

        this.model = Alpine.reactive({
            typeDefinition: null,
            propertyTypes: [
                'BINARY',
                'BOOLEAN',
                'BOOLEAN LIST',
                'DATE',
                'DATE LIST',
                'DATETIME',
                'DATETIME LIST',
                'INTEGER',
                'INTEGER LIST',
                'STRING',
                'STRING LIST'
            ],
            columns: [
                'Name',
                'Type',
                'Description',
                'Actions'
            ]
        });

        console.info("Created schema editor", this.model);
    }

    load(typeDefinition) {
        this.model.typeDefinition = new TypeDefinition(typeDefinition);
        console.info(this.model.typeDefinition);
    }

    injectStyles() {
        const styleEl = document.createElement('style');
        styleEl.id = this.stylesId;
        styleEl.textContent = `
            .schema-editor {
                --background-color: #0f1419;  /* Background color */
                --grid-border-color: #30363d; /* Grid border color */
                --grid-header-background-color: #1d2025; /* Grid header color */
                --button-background-color: #1a222b; /* Button background color */
                --grid-header-text-color: #657082; /* Grid header text color */
                --highlight-color: #95a6bf;
            
                font-family: sans-serif;
                background-color: var(--grid-border-color);
                margin: 40px;
                color: #e6edf3;
                padding: 1px;
                display: grid;
                grid-template-columns: min-content min-content 2fr min-content;
                align-items: center;
                grid-gap: 1px;
                
                .schema-header {
                    grid-column: 1 / span 4;
                    display: flex;
                    flex-direction: column;
                    background-color: var(--background-color);
                    margin-top: -1px;
                    margin-left: -1px;
                    margin-right: -1px;
                    padding-bottom: 20px;
                    
                    .schema-type-name {
                        font-size: 1.5em;
                        font-weight: bold;
                    }
                    
                    .schema-type-description {
                        color: var(--highlight-color);
                    }
                }
                
                .sectionHeader {
                    grid-column: 1 / span 4;
                    display: flex;
                    background-color: var(--background-color);
                    margin-top: -1px;
                    margin-left: -1px;
                    margin-right: -1px;
                    padding-bottom: 20px;
                    
                    .sectionName {
                        font-size: 1.5em;
                        font-weight: bold;
                    }
                }
                
                .grid-item {
                    background-color: var(--background-color);
                    padding: 10px;
                    box-sizing: border-box;
                    display: flex;
                    gap: 10px;
                    align-items: center;
                    
                    height: 100%;
                    
                    &.new {
                        color: #74cc64;
                    }
                    
                    &.modified {
                        color: #f0bd24;
                    }
                    
                    &.deleted {
                        color: #b54233;
                    }
                    
                    .move-group-input {
                        padding: 4px;
                    }
    
                    > * {
                        flex: 1;
                    }
                }
                
                .form-header {
                    display: contents;
                    background-color: var(--grid-header-background-color);
                    color: var(--grid-header-text-color);
                    font-weight: bold;
    
                    .grid-item {
                        background-color: inherit;
                        justify-content: center;
                    }
                }
                
                .property-group {
                    grid-column: 1 / span 4;
                    padding-top: 30px;
                    padding-left: 0px;
                    font-weight: bold;
                    font-size: 1.2em;
                    margin-left: -1px;
                    margin-right: -1px;
                    color: var(--highlight-color);
                    
                    input {
                        font-weight: bold;
                        flex: 3;
                        
                        &.pending {
                            color: #7f8da3;
                            
                            &::placeholder {
                                color: #f7b931;
                            }
                        }
                    }
                   
                }
                
                label {
                    display: inline-block;
                    vertical-align: middle;
                }
    
                input[type=text] {
                    font-size: inherit;
                    color: inherit;
                    padding: 5px;
                    border: solid 1px transparent;
                    background-color: transparent;
                    
                    &:hover {
                        background-color: var(--background-color);
                        border: solid 1px var(--highlight-color);
                    }
                    
                    &:focus {
                        background-color: var(--background-color);
                        border: solid 1px var(--highlight-color);
                    }
                }
    
                select {
                    font-size: inherit;
                    padding: 5px;
                    border: solid 1px transparent;
                    background-color: transparent;
                    color: inherit;
                   
                    &:hover {
                        background-color: var(--background-color);
                        appearance: auto;
                        border: solid 1px var(--grid-border-color);
                    }
                    
                    &.displaySelect {
                        appearance: none;
                        
                        &:hover {
                            border: solid 1px transparent;
                        }
                    }
                    
                    &.leftArrow {
                        padding: 10px 14px 10px 40px;
                        background-color: var(--background-color);
                        border: 1px solid transparent;
                        border-radius: 6px;
                        
                        font-family: inherit;
                        cursor: pointer;
                        
                        /* Remove native arrow */
                        appearance: none;
                        -webkit-appearance: none;
                        -moz-appearance: none;
                        
                        /* Chevron-style arrow */
                        background-image: url('data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><path fill="none" stroke="%239198a1" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M4 6l4 4 4-4"/></svg>');
                        background-repeat: no-repeat;
                        background-position: left 12px center;
                        background-size: 16px 16px;  
                    }
                }
                
                .group-actions {
                    * {
                        flex: 0;
                    }
                    .add-property-button {
                        text-wrap: nowrap;
                        padding-left: 10px;
                        padding-right: 10px;
                    }
                    .group-delete-button {
                        margin-left: auto;
                    }
                }
                
                .open-section {
                    padding: 20px 0px;
                    grid-column: 1 / span 4;  
                    
                    margin-left: -1px; 
                    margin-right: -1px;
                    margin-bottom: -1px;
                }
                
                .propertyControl {
                    visibility: hidden;
                    
                    &.visible {
                        visibility: visible;
                    }
                    
                    &.omitted {
                        display: none;
                    }
                    
                    &.fixed {
                        flex: 0;
                    }
                }
                
                .bottom-controls-section {
                    display: flex;
                    flex-direction: column;
                    align-items: stretch;
                   
                    .add-group-section {
                        .add-group-button {
                            text-wrap: nowrap;
                            flex: 0;
                        }
                    }
                    
                    .form-action-section {
                        
                    }
                }
            }
            
            button {
                font-size: inherit;
                padding: 5px 10px;
                border-radius: 3px;
                background-color: var(--button-background-color);
                color: #e6edf3;
                border: solid 1px var(--grid-border-color);
                cursor: pointer;
                text-wrap: nowrap;
                
                i {
                    margin-right: 10px;
                }
                
                &.imageOnly {
                    i {
                        margin-left: 5px;
                        margin-right: 5px;
                    }
                }
            }
        `;
        document.head.appendChild(styleEl);
    }

    getTemplate() {
        return `
            <template x-if="model.typeDefinition">
                <div class="schema-editor" x-data="{model}">
                    <div class="schema-header">
                        <input type="text" class="schema-type-name" x-model.lazy="model.typeDefinition.name.value">
                        <input type="text" class="schema-type-description" x-model.lazy="model.typeDefinition.description.value">
                    </div>
                    
                    <div class="sectionHeader">
                        <button title="Collapse" class="imageOnly"><i class="fas fa-caret-up"></i></button>
                        <div class="sectionName">Properties</div>
                    </div>
                        
                    <template x-for="group in model.typeDefinition.propertyGroups">
                        <div style="display:contents">
                            
                            <template x-if="group.isNamedGroup">
                                <div class="grid-item property-group">
                                    <input placeholder="Enter a property group name" type="text" class="group-name-input" :class="{pending: group.name == null}" x-model.lazy="group.name">
                                </div>
                            </template>
                        
                            <!-- Column header -->    
                            <template x-if="group.expanded">
                                <div class="form-header">
                                    <template x-for="column in model.columns">
                                        <div class="grid-item" x-text="column"></div>
                                    </template>
                                </div>
                            </template>
                           
                            <!-- Properties -->
                            <template x-if="group.expanded">
                                <template x-for="(property, index) in group.properties">
                                    <div style="display:contents">
                                        <div class="grid-item" :class="{new: group.isNew(property), modified: group.isNormal(property) && property.name.modified, deleted: group.isDeleted(property)}">
                                            <input type="text" class="name-input" :data-ref="property.referenceId" x-model.lazy="property.name.value">
                                        </div>
                                        <div class="grid-item" :class="{new: group.isNew(property), modified: group.isNormal(property) && property.type.modified, deleted: group.isDeleted(property)}">
                                            <template x-if="property.id == null">
                                                <select x-model.lazy="property.type.value">
                                                    <template x-for="type in model.propertyTypes">
                                                        <option :value="type" x-text="type" :selected="type == property.type.value"></option>
                                                    </template>
                                                </select>
                                            </template>
                                            <template x-if="property.id != null">
                                                <select class="displaySelect" disabled>
                                                    <option :value="property.type.value" x-text="property.type.value" selected></option>
                                                </select>
                                            </template>        
                                        </div>
                                        <div class="grid-item" :class="{new: group.isNew(property), modified: group.isNormal(property) && property.description.modified, deleted: group.isDeleted(property)}">
                                            <input type="text" x-model.lazy="property.description.value">
                                        </div>
                                        <div class="grid-item" :class="{new: group.isNew(property), deleted: group.isDeleted(property)}">
                                            <select class="propertyControl leftArrow" :class="{omitted: model.typeDefinition.propertyGroups.length == 1, visible: !group.isDeleted(property)}" @change="moveProperty(group, property, $event.target)">
                                                <option disabled selected value="">Move</option>
                                                <template x-for="targetGroup in model.typeDefinition.propertyGroups.filter(g => g.name != group.name)">
                                                    <option :value="targetGroup.name" x-text="targetGroup.displayName"></option>
                                                </template>
                                            </select>
                                            <button title="Undo changes" class="fixed propertyControl imageOnly" :class="{visible: group.isNormal(property) && property.modified}"  @click="property.clearChanges()"><i class="fas fa-undo"></i></button>
                                            
                                            <template x-if="group.isDeleted(property)">
                                                <button title="Restore property" class="fixed propertyControl imageOnly" :class="{visible: group.isDeleted(property)}" @click="group.unremoveProperty(property)"><i class="fas fa-trash-restore"></i></button>
                                            </template>
                                            <template x-if="!group.isDeleted(property)">
                                                <button title="Remove property" class="fixed propertyControl imageOnly" :class="{visible: !group.isDeleted(property)}" @click="group.removeProperty(property)"><i class="fas fa-trash"></i></button>
                                            </template>
                                        </div>
                                    </div>
                                </template>
                            </template>
                            
                            
                            <!-- Group actions-->    
                            <div class="grid-item group-actions" style="grid-column: 1 / span 4">
                                <template x-if="group.expanded">
                                    <button title="Collapse" class="imageOnly" @click="group.collapse()"><i class="fas fa-caret-up"></i></button>
                                </template>
                                <template x-if="!group.expanded">
                                    <button title="Expand" class="imageOnly" @click="group.expand()"><i class="fas fa-caret-down"></i></button>
                                </template>
                            
                                <template x-if="group.expanded">
                                    <button class="add-property-button" @click="addProperty(group)"><i class="fas fa-plus"></i> New property</button>
                                </template>
                                
                                <template x-if="group.expanded && group.properties.length > 0 && model.typeDefinition.propertyGroups.length > 1">
                                    <select class="leftArrow" @change="moveProperties(group, $event.target)">
                                        <option disabled selected value="">Move all properties</option>
                                        <template x-for="targetGroup in model.typeDefinition.propertyGroups.filter(g => g.name != group.name)">
                                            <option :value="targetGroup.name" x-text="targetGroup.displayName"></option>
                                        </template>
                                    </select>
                                </template>
                                
                                <template x-if="group.expanded && group.properties.length > 0">
                                    <button class="delete-properties-button" @click="group.removeProperties()"><i class="fas fa-trash"></i>Delete all properties</button>
                                </template>
                                
                                <template x-if="group.expanded && group.name != null && group.properties.length == 0">
                                    <button class="group-delete-button" @click="deleteGroup(group)"><i class="fas fa-trash"></i>Delete group</button>
                                </template>    
                            </div>
    
                        </div>
                    </template>
                    
                    <div class="grid-item bottom-controls-section open-section">
                        <template x-if="model.typeDefinition.propertyGroups.length > 0">
                            <div class="add-group-section">
                                <button class="add-group-button" @click="addPropertyGroup()"><i class="fas fa-plus"></i> New property group</button>
                            </div>
                        </template>             
                    </div>
                    
                    <div class="sectionHeader">
                        <button title="Collapse" class="imageOnly"><i class="fas fa-caret-up"></i></button>
                        <div class="sectionName">Links</div>
                    </div>
                    
                    <div class="grid-item bottom-controls-section open-section">
                        <template x-if="model.typeDefinition.modified">
                            <div class="grid-item form-action-section open-section">
                                <button class="save-changes-button"><i class="fas fa-save"></i> Save</button>
                                <button class="cancel-changes-button"><i class="fas fa-ban"></i> Cancel</button>
                            </div>
                        </template> 
                    </div>
                    
                </div>
            </template>
        `;
    }

    loadFontAwesomeIcons() {
        if (!document.querySelector('link[href*="fontawesome"]')) {
            const link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css';
            document.head.appendChild(link);
        }
    }

    addProperty(group) {
        let newProperty = group.addProperty();
        this.focusProperty(newProperty);
    }

    moveProperty(group, property, targetGroupEl) {
        let groupName = targetGroupEl.value;
        if (groupName === "null") {
            groupName = null;
        }

        let targetGroup = this.model.typeDefinition.propertyGroups.find(g => g.name === groupName);
        if (targetGroup == null) {
            console.error("Could not find target group with name", groupName);
        } else {
            group.pullProperty(property);
            targetGroup.pushProperty(property);
            targetGroupEl.selectedIndex = 0;
        }
    }

    moveProperties(group, targetGroupEl) {
        let groupName = targetGroupEl.value;
        if (groupName === "null") {
            groupName = null;
        }

        let targetGroup = this.model.typeDefinition.propertyGroups.find(g => g.name === groupName);
        if (targetGroup == null) {
            console.error("Could not find target group with name", groupName);
        } else {
            targetGroup.properties.push(...group.properties);
            group.properties.splice(0);
            targetGroupEl.selectedIndex = 0;
        }
    }

    focusProperty(property) {
        this.$nextTick(() => {
            const refId = property.referenceId;
            const nameInputs = document.querySelectorAll('.name-input[data-ref="' + refId + '"]');

            if (nameInputs.length > 0) {
                const lastInput = nameInputs[nameInputs.length - 1];
                lastInput.focus();
                lastInput.select();

                // Optional: Scroll into view
                lastInput.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        });
    }

    addPropertyGroup() {
        this.model.typeDefinition.propertyGroups.push(new PropertyGroup({id: null, name: null, properties: []}));
    }

    deleteGroup(group) {
        let properties = group.properties;
        let emptyGroup = this.model.typeDefinition.propertyGroups.find(g => !g.isNamedGroup);
        emptyGroup.properties.push(...properties);
        this.model.typeDefinition.propertyGroups.splice(this.model.typeDefinition.propertyGroups.indexOf(group), 1);
    }
}

class EditableProperty {
    _name;
    _originalValue;
    _newValue;

    modified = false;

    constructor(name, value) {
        this._name = name;
        this._originalValue = value;
    }

    set value(value) {
        if (this._newValue) {
            if (this._originalValue === value) {
                console.info(`Reverting changed value from ${this._newValue} back to ${this._originalValue} on ${this.name}`);
                this._newValue = null;
                this.modified = false;
            } else {
                console.info(`Setting new value ${this._newValue} on ${this._name}`);
                this._newValue = value;
                this.modified = true;
            }
        } else {
            console.info(`Changing value from ${this._originalValue} to ${value} on ${this._name}`);
            this._newValue = value;
            this.modified = true;
        }
    }

    get value() {
        return this._newValue ? this._newValue : this._originalValue;
    }

    clearChanges() {
        this._newValue = null;
        this.modified = false;
    }
}

class Property {
    referenceId;
    id;
    name;
    type;
    description;

    constructor(id, name, type, description) {
        this.referenceId = crypto.randomUUID();
        this.id = id;
        this.name = new EditableProperty(name, name);
        this.type = new EditableProperty(name, type);
        this.description = new EditableProperty(name, description);
        console.info("Created property", this);
    }

    get modified() {
        return this.name.modified || this.type.modified || this.description.modified;
    }

    clearChanges() {
        this.name.clearChanges();
        this.type.clearChanges();
        this.description.clearChanges();
    }

}

class PropertyGroup {
    id;
    name;
    isNamedGroup = true;
    properties;
    addedProperties = [];
    removedProperties = [];
    deletedProperties = [];
    expanded = true;

    constructor(obj) {
        Object.assign(this, obj);
        this.properties = this.properties.map((field, index, array) => {
            return new Property(field.id, field.name, field.type, field.description);
        });
    }

    addProperty() {
        let newProperty = new Property(null, null, null, null);
        this.properties.push(newProperty);
        return newProperty;
    }

    pullProperty(property) {
        this.properties.splice(this.properties.indexOf(property), 1);
        this.removedProperties.push(property);
    }

    pushProperty(property) {
        this.properties.push(property);
        this.addedProperties.push(property);
    }

    get displayName() {
        return this.name || '(No property group)';
    }

    removeProperty(prop) {
        if (this.isNew(prop)) {
            this.properties.splice(this.properties.indexOf(prop), 1);
        } else {
            this.deletedProperties.push(prop);
        }
    }

    removeProperties() {
        for (const property of this.properties) {
            this.removeProperty(property);
        }
    }

    unremoveProperty(prop) {
        let idx = this.deletedProperties.indexOf(prop);
        if (idx >= 0) {
            this.deletedProperties.splice(idx, 1);
        }
    }

    isNew(property) {
        return property.id == null;
    }

    isNormal(property) {
        return !this.isNew(property) && !this.isDeleted(property);
    }

    isDeleted(property) {
        return this.deletedProperties.includes(property);
    }

    collapse() {
        this.expanded = false;
    }

    expand() {
        this.expanded = true;
    }

    get modified() {
        let propsDeleted = this.deletedProperties.length > 0;
        let propsAdded = this.addedProperties.length > 0;
        let propModified = this.properties.some(p => p.modified);
        return propsDeleted | propsAdded || propModified;
    }
}

class Links {
    incoming;
    outgoing;

    constructor(obj) {
        this.incoming = obj.incoming.map(link => new Link(link));
        this.outgoing = obj.outgoing.map(link => new Link(link));
    }
}

class Link {
    name;
    relatedType;
    reverseName;

    constructor(obj) {
        Object.assign(this, obj);
    }
}

class TypeDefinition {
    name;
    description;
    propertyGroups;
    linkx;

    constructor(obj) {
        this.name = new EditableProperty(obj.name, obj.name);
        this.description = new EditableProperty(obj.description, obj.description);
        this.propertyGroups = obj.propertyGroups.map((group, index, array) => {
            return new PropertyGroup(group);
        });
        this.linkx = new Links(obj.links);
        console.info("Created type definition", this);
    }

    get modified() {
        let definitionModified = this.name.modified || this.description.modified;
        let groupModified = this.propertyGroups.some(g => g.modified);
        return definitionModified || groupModified;
    }
}