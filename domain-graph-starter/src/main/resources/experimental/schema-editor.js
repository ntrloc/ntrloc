class SchemaEditor {

    constructor(data) {
        this.stylesId = 'schema-editor-styles';

        if (!document.getElementById(this.stylesId)) {
            this.injectStyles();
        }

        let properties = data.items.map((field, index, array) => {
            return new Property(field.id, field.name, field.type, field.description);
        });
        console.info("Got properties", properties);

        let propertyGroups = data.propertyGroups.map((group, index, array) => {
           return new PropertyGroup(group);
        });
        console.info("Got property groups", propertyGroups);

        this.data = {
            properties: properties,
            propertyGroups: propertyGroups,
            propertyTypes: [
                'BOOLEAN',
                'DATETIME',
                'STRING'
            ],
            columns: [
                'Name',
                'Type',
                'Description',
                'Actions'
            ]
        };
        this.deletedProperties = [];
        this.state = {
            isAdding: false
        };
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
                grid-template-columns: repeat(4, 1fr);
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
                    }
                }
                
                label {
                    display: inline-block;
                    vertical-align: middle;
                }
    
                input[type=text] {
                    font-size: inherit;
                    border: solid 1px #ccc;
                    padding: 5px;
                    border: none;
                    background-color: transparent;
                    
                    &:hover {
                       background-color: white;
                       border: solid 1px #ccc;
                    }
                }
    
                select {
                    font-size: inherit;
                    padding: 5px;
                    appearance: none;
                    border: none;
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
                }
                
                button {
                    cursor: pointer;
                }
            }
        `;
        document.head.appendChild(styleEl);
    }

    getTemplate() {
        return `
            <div class="schema-editor-contents" style="display:contents" x-data="{data}">
                <div class="schema-editor">
                    <!-- Form header -->    
                        <div class="form-header">
                            <template x-for="column in data.columns">
                                <div class="grid-item" x-text="column"></div>
                            </template>
                        </div>
                        <!-- Form rows -->
                        <template x-for="(property, index) in data.properties">
                            <div style="display:contents">
                                <div class="grid-item" :class="{new: isNew(property), modified: isNormal(property) && property.name.modified, deleted: isDeleted(property)}">
                                    <input type="text" class="name-input" :data-ref="property.referenceId" x-model.lazy="property.name.value">
                                </div>
                                <div class="grid-item" :class="{new: isNew(property), modified: isNormal(property) && property.type.modified, deleted: isDeleted(property)}">
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
                                <div class="grid-item" :class="{new: isNew(property), modified: isNormal(property) && property.description.modified, deleted: isDeleted(property)}">
                                    <input type="text" x-model.lazy="property.description.value">
                                </div>
                                <div class="grid-item" :class="{new: isNew(property), deleted: isDeleted(property)}">
                                    <template x-if="isDeleted(property)">
                                        <button @click="unremoveProperty(property)">Restore</button>
                                    </template>
                                    <template x-if="isNormal(property) && property.modified">
                                        <button @click="property.clearChanges()">Clear changes</button>
                                    </template>
                                    <template x-if="!isDeleted(property)">
                                        <input 
                                                :list="'group-list-' + (property.id || index)" 
                                                placeholder="Move to group..."
                                                @change="movePropertyToGroup(property, $event.target.value); $event.target.value = ''"
                                                class="move-group-input">
                                    </template>
                                    <template x-if="!isDeleted(property)">
                                        <button @click="removeProperty(property)">Remove</button>
                                    </template>
                                    <datalist :id="'group-list-' + (property.id || index)">
                                        <option value="(No group)">No group</option>
                                        <template x-for="group in data.propertyGroups">
                                            <option :value="group.name" x-text="group.name"></option>
                                        </template>
                                    </datalist>
                                </div>
                            </div>
                        </template>
                        
                        <div class="grid-item" style="grid-column: 1 / span 4">
                            <button class="addPropertyButton" @click="addProperty()">New property</button>
                        </div>
                        
                        <!-- Property groups -->
                        <template x-for="group in data.propertyGroups">
                            <div style="display:contents">
                                <div class="grid-item property-group">
                                    <input type="text" class="group-name-input" x-model.lazy="group.name">
                                </div>
                                
                                <!-- Group properties -->
                                <template x-for="(property, index) in group.properties">
                                    <div style="display:contents">
                                        <div class="grid-item" :class="{new: isNew(property), modified: isNormal(property) && property.name.modified, deleted: isDeleted(property)}">
                                            <input type="text" class="name-input" :data-ref="property.referenceId" x-model.lazy="property.name.value">
                                        </div>
                                        <div class="grid-item" :class="{new: isNew(property), modified: isNormal(property) && property.type.modified, deleted: isDeleted(property)}">
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
                                        <div class="grid-item" :class="{new: isNew(property), modified: isNormal(property) && property.description.modified, deleted: isDeleted(property)}">
                                            <input type="text" x-model.lazy="property.description.value">
                                        </div>
                                        <div class="grid-item" :class="{new: isNew(property), deleted: isDeleted(property)}">
                                            <template x-if="isDeleted(property)">
                                                <button @click="unremoveProperty(property)">Restore</button>
                                            </template>
                                            <template x-if="isNormal(property) && property.modified">
                                                <button @click="property.clearChanges()">Clear changes</button>
                                            </template>
                                            <template x-if="!isDeleted(property)">
                                                <button @click="removeProperty(property)">Remove</button>
                                            </template>
                                        </div>
                                    </div>
                                </template>
                                
                                <div class="grid-item" style="grid-column: 1 / span 4">
                                    <button class="addPropertyButton" @click="addPropertyToGroup(group)">New property</button>
                                </div>
                            </div>
                            
                        </template>
                        
                        
                </div>
            </div>
        `;
    }

    addProperty() {
        let newProperty = new Property(null, null, null, null);
        this.data.properties.push(newProperty);
        this.focusProperty(newProperty);
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

    addPropertyToGroup(propertyGroup) {
        let newProperty = new Property(null, null, null, null);
        propertyGroup.properties.push(newProperty);
        this.focusProperty(newProperty);
    }

    removeProperty(prop) {
        if (this.isNew(prop)) {
            this.data.properties.splice(this.data.properties.indexOf(prop), 1);
        } else {
            this.deletedProperties.push(prop);
        }
    }

    // TODO: add removePropertyFromGroup

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
    properties;

    constructor(obj) {
        Object.assign(this, obj);
        this.properties = this.properties.map((field, index, array) => {
            return new Property(field.id, field.name, field.type, field.description);
        });
    }
}