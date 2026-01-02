class SchemaEditor {

    constructor() {
        this.loadFontAwesomeIcons();

        this.stylesId = 'schema-editor-styles';

        if (!document.getElementById(this.stylesId)) {
            this.injectStyles();
        }

        this.data = Alpine.reactive({
            propertyGroups: [],
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

        console.info("Created schema editor", this.data);
    }

    load(newData) {
        console.info("Loading data", this.data);
        let propertyGroups = newData.propertyGroups.map((group, index, array) => {
            return new PropertyGroup(group);
        });

        this.data.propertyGroups = propertyGroups;
        console.info("Loaded data", this.data);
    }

    injectStyles() {
        const styleEl = document.createElement('style');
        styleEl.id = this.stylesId;
        styleEl.textContent = `
            .schema-editor {
                font-family: sans-serif;
                background-color: black;
                padding: 1px;
                display: grid;
                grid-template-columns: min-content min-content 2fr min-content;
                align-items: center;
                grid-gap: 1px;
                
                .grid-item {
                    background-color: white;
                    padding: 10px;
                    box-sizing: border-box;
                    display: flex;
                    align-items: center;
                    width: 100%;
                    height: 100%;
                    
                    &.new {
                        background-color: #d4ffcc;
                    }
                    
                    &.modified {
                        background-color: #fff8b0;
                    }
                    
                    &.deleted {
                        background-color: #fac3cd;
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
                    background-color: #ccc;
                    font-weight: bold;
    
                    .grid-item {
                        background-color: inherit;
                        justify-content: center;
                    }
                }
                
                .property-group {
                    grid-column: 1 / span 4;
                    padding-top: 30px;
                    font-weight: bold;
                    font-size: 1.2em;
                    
                    input {
                        font-weight: bold;
                        flex: 3;
                    }
                    
                    .group-delete-button {
                        cursor: pointer;
                        flex: 0;
                    }
                    
                }
                
                label {
                    display: inline-block;
                    vertical-align: middle;
                }
    
                input[type=text] {
                    font-size: inherit;
                    padding: 5px;
                    border: solid 1px transparent;
                    background-color: transparent;
                    
                    &:hover {
                        background-color: white;
                        border: solid 1px #ccc;
                    }
                    
                    &:focus {
                        background-color: white;
                        border: solid 1px #ccc;
                    }
                }
    
                select {
                    font-size: inherit;
                    padding: 5px;
                    
                    border: solid 1px transparent;
                    background-color: transparent;
                   
                    &:hover {
                        background-color: white;
                        appearance: auto;
                        border: solid 1px #ccc;
                    }
                }
    
                button {
                    font-size: inherit;
                    padding: 5px;
                    border-radius: 3px;
                    border: 1px solid #666666;
                    cursor: pointer;
                }
                
                .add-group-button {
                    margin-top: 30px;
                }
                
                .add-property-button {
                    flex: 0; 
                    text-wrap: nowrap;
                    padding-left: 10px;
                    padding-right: 10px;
                }
                
                .propertyControl {
                    visibility: hidden;
                    
                    &.display {
                        visibility: visible;
                    }
                }
                
            }
        `;
        document.head.appendChild(styleEl);
    }

    getTemplate() {
        return `
            <div class="schema-editor-contents" style="display:contents" x-data="{data}">
                <template x-for="group in data.propertyGroups">
                    <div class="schema-editor">
                        <template x-if="group.isNamedGroup">
                            <div class="grid-item property-group">
                                <i class="fa-regular fa-circle-xmark group-delete-button" @click="deleteGroup(group)"></i>
                                <input type="text" class="group-name-input" x-model.lazy="group.name">
                            </div>
                        </template>
                    
                    
                        <!-- Form header -->    
                        <div class="form-header">
                            <template x-for="column in data.columns">
                                <div class="grid-item" x-text="column"></div>
                            </template>
                        </div>
                        
                       
                        <div style="display:contents">
                            
                            <template x-for="(property, index) in group.properties">
                                <div style="display:contents">
                                    <div class="grid-item" :class="{new: group.isNew(property), modified: group.isNormal(property) && property.name.modified, deleted: group.isDeleted(property)}">
                                        <input type="text" class="name-input" :data-ref="property.referenceId" x-model.lazy="property.name.value">
                                    </div>
                                    <div class="grid-item" :class="{new: group.isNew(property), modified: group.isNormal(property) && property.type.modified, deleted: group.isDeleted(property)}">
                                        <template x-if="property.id == null">
                                            <select x-model.lazy="property.type.value">
                                                <template x-for="type in data.propertyTypes">
                                                    <option :value="type" x-text="type" :selected="type == property.type.value"></option>
                                                </template>
                                            </select>
                                        </template>
                                        <template x-if="property.id != null">
                                            <div x-text="property.type.value"></div>
                                        </template>        
                                    </div>
                                    <div class="grid-item" :class="{new: group.isNew(property), modified: group.isNormal(property) && property.description.modified, deleted: group.isDeleted(property)}">
                                        <input type="text" x-model.lazy="property.description.value">
                                    </div>
                                    <div class="grid-item" :class="{new: group.isNew(property), deleted: group.isDeleted(property)}">
                                        <button class="propertyControl" :class="{display: group.isDeleted(property)}" @click="group.unremoveProperty(property)">Restore</button>
                                        <button class="propertyControl" :class="{display: !group.isDeleted(property)}" @click="group.removeProperty(property)">Remove</button>
                                        <button class="propertyControl" :class="{display: group.isNormal(property) && property.modified}"  @click="property.clearChanges()">Clear changes</button>
                                        <select @change="moveProperty(group, property, $event.target)">
                                            <option disabled selected value="">Move to group</option>
                                            <template x-for="targetGroup in data.propertyGroups.filter(g => g.name != group.name)">
                                                <option :value="targetGroup.name" x-text="targetGroup.displayName"></option>
                                            </template>
                                        </select>
                                    
                                    
                                        <!--
                                        <template x-if="group.isDeleted(property)">
                                            <button @click="group.unremoveProperty(property)">Restore</button>
                                        </template>
                                        <template x-if="!group.isDeleted(property)">
                                            <button @click="group.removeProperty(property)">Remove</button>
                                        </template>
                                        <template x-if="group.isNormal(property) && property.modified">
                                            <button @click="property.clearChanges()">Clear changes</button>
                                        </template>
                                        <select @change="moveProperty(group, property, $event.target)">
                                            <option disabled selected value="">Move to group</option>
                                            <template x-for="targetGroup in data.propertyGroups.filter(g => g.name != group.name)">
                                                <option :value="targetGroup.name" x-text="targetGroup.displayName"></option>
                                            </template>
                                        </select>
                                        -->
                                    </div>
                                </div>
                            </template>
                            
                            <div class="grid-item" style="grid-column: 1 / span 4">
                                <button class="add-property-button" @click="addProperty(group)">New property</button>
                            </div>
                        </div>
                        
                        
                    </div>
                </template>
                
                <div class="grid-item" style="grid-column: 1 / span 4">
                    <button class="add-group-button" @click="addPropertyGroup()">New property group</button>
                </div>
                
            </div>
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
        // window.alert("Move property " + property.name.value + " from group " + group.name + " to group " + targetGroupEl.value);

        let targetGroup = this.data.propertyGroups.find(g => g.name === targetGroupEl.value);
        targetGroup.properties.push(property);
        group.removeProperty(property);

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
        this.data.propertyGroups.push(new PropertyGroup({id: null, name: null, properties: []}));
    }

    deleteGroup(group) {
        let properties = group.properties;
        let emptyGroup = this.data.propertyGroups.find(g => !g.isNamedGroup);
        emptyGroup.properties.push(...properties);
        this.data.propertyGroups.splice(this.data.propertyGroups.indexOf(group), 1);
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
    deletedProperties = [];

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
}