class SchemaEditor {

    constructor() {
        this.loadFontAwesomeIcons();

        this.stylesId = 'schema-editor-styles';

        if (!document.getElementById(this.stylesId)) {
            this.injectStyles();
        }

        this.model = Alpine.reactive({
            typeDefinition: null,
            propertySchema: {
                columns: [
                    'Name',
                    'Type',
                    'Description',
                    'Actions'
                ],
                types: [
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
                ]
            }
        });

        console.info("Created schema editor", this.model);
    }

    load(typeDefinition) {
        let definitionModel = new TypeDefinition(typeDefinition);
        this.model.typeDefinition = new TypeDefinitionViewModel(definitionModel);
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
                margin: 40px;
                color: #e6edf3;
            }
            
            .schema-header {
                display: flex;
                flex-direction: column;
                background-color: var(--background-color);
                margin-bottom: 20px;
                
                .schema-type-name {
                    font-size: 1.5em;
                    font-weight: bold;
                }
                
                .schema-type-description {
                    color: var(--highlight-color);
                }
            }
            
            .sectionHeader {
                display: flex;
                background-color: var(--background-color);
                margin-top: 10px;
                margin-bottom: 10px;
              
                .sectionName {
                    font-size: 1.5em;
                    font-weight: bold;
                }
            }
            
            .propertyEditorSection {
                margin-top: 10px;
                margin-bottom: 20px;
                margin-left: 35px;
                padding: 1px;
                background-color: var(--grid-border-color);
                
                display: grid;
                grid-template-columns: min-content min-content 2fr min-content;
                align-items: center;
                grid-gap: 1px;
                
                .property-group-header {
                    grid-column: 1 / span 4;
                   
                    font-weight: bold;
                    font-size: 1.2em;
                    margin-top: -1px;
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
                    
                    button {
                        flex: 0;
                    }
                }
                
                .property-group-spacer {
                    background-color: var(--background-color);
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
                
                .open-section {
                    padding: 20px 0px;
                    grid-column: 1 / span 4;  
                    
                    margin-left: -1px; 
                    margin-right: -1px;
                    margin-bottom: -1px;
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
            
            /* Generic element styles that apply throughout the editor */
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
                
                &.readonly {
                    &:hover {
                        border: solid 1px transparent;
                        background-color: transparent;
                    }
                    
                    &:focus {
                        border: solid 1px transparent;
                        background-color: transparent;
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
        `;
        document.head.appendChild(styleEl);
    }

    getTemplate() {
        return `
            <template x-if="model.typeDefinition">
                <div class="schema-editor" x-data="{model, get td() { return model.typeDefinition; }}">
                    <!-- Type definition header -->
                    <div class="schema-header">
                        <input type="text" class="schema-type-name" x-model.lazy="td.name.value">
                        <input type="text" class="schema-type-description" x-model.lazy="td.description.value">
                    </div>
                    
                    <!-- Properties header -->
                    <div class="sectionHeader"">
                        <button :title="td.propertiesSection.expanded ? 'Collapse' : 'Expand'" 
                                class="imageOnly" 
                                @click="td.propertiesSection.toggle()">
                            <i :class="td.propertiesSection.expanded ? 'fas fa-caret-up' : 'fas fa-caret-down'"></i>
                        </button>
                        <div class="sectionName">Properties</div>
                    </div>
                    
                    <template x-if="td.propertiesSection.expanded">
                        <div class="propertyEditorSection">
                            <template x-for="group in td.propertiesSection.propertyGroups">
                                <div style="display:contents">
                               
                                    <!-- Property group header --> 
                                    <div class="grid-item property-group-header">
                                        <button :title="group.expanded ? 'Collapse' : 'Expand'" 
                                                class="imageOnly" 
                                                @click="group.toggle()">
                                            <i :class="group.expanded ? 'fas fa-caret-up' : 'fas fa-caret-down'"></i>
                                        </button>
                                    
                                        <input placeholder="Enter a property group name" type="text" class="group-name-input" :inert="!group.isNameEditable()" :class="{pending: group.name == null, readonly: !group.isNameEditable()}" x-model.lazy="group.name">
                                    
                                        <template x-if="!group.isDeleted() && group.isDeletable()">
                                            <button class="group-delete-button" @click="group.delete()"><i class="fas fa-trash"></i>Delete group</button>
                                        </template>
                                        
                                        <template x-if="group.isDeleted()">
                                            <button class="group-delete-button" @click="group.undelete()"><i class="fas fa-trash-restore"></i>Restore group</button>
                                        </template>  
                                    </div>
                                    
                                    <!-- Column header -->    
                                    <div x-show="group.expanded" class="form-header">
                                        <template x-for="column in model.propertySchema.columns">
                                            <div class="grid-item" x-text="column"></div>
                                        </template>
                                    </div>
                                    
                                    <!-- Group properties -->
                                    <template x-if="group.expanded">
                                        <template x-for="(property, index) in group.properties">
                                            <div style="display:contents">
                                                <div class="grid-item" :class="{new: group.isNewProperty(property), modified: group.isNormalProperty(property) && property.name.modified, deleted: group.isDeletedProperty(property)}">
                                                    <input type="text" class="name-input" :data-ref="property.referenceId" x-model.lazy="property.name.value">
                                                </div>
                                                <div class="grid-item" :class="{new: group.isNewProperty(property), modified: group.isNormalProperty(property) && property.type.modified, deleted: group.isDeletedProperty(property)}">
                                                    <template x-if="property.id == null">
                                                        <select x-model.lazy="property.type.value">
                                                            <template x-for="type in model.propertySchema.types">
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
                                                <div class="grid-item" :class="{new: group.isNewProperty(property), modified: group.isNormalProperty(property) && property.description.modified, deleted: group.isDeletedProperty(property)}">
                                                    <input type="text" x-model.lazy="property.description.value">
                                                </div>
                                                <div class="grid-item" :class="{new: group.isNewProperty(property), deleted: group.isDeletedProperty(property)}">
                                                    <select class="propertyControl leftArrow" :class="{omitted: td.propertiesSection.propertyGroups.length == 1, visible: !group.isDeleted(property)}" @change="moveProperty(group, property, $event.target)">
                                                        <option disabled selected value="">Move</option>
                                                        <template x-for="targetGroup in td.propertiesSection.propertyGroups.filter(g => g.name != group.name && !g.isDeleted())">
                                                            <option :value="targetGroup.name" x-text="targetGroup.name"></option>
                                                        </template>
                                                    </select>
                                                    <button title="Undo changes" class="fixed propertyControl imageOnly" :class="{visible: group.isNormalProperty(property) && property.modified}"  @click="property.clearChanges()"><i class="fas fa-undo"></i></button>
                                                    
                                                    <template x-if="group.isDeletedProperty(property)">
                                                        <button title="Restore property" class="fixed propertyControl imageOnly" :class="{visible: group.isDeletedProperty(property)}" @click="group.unremoveProperty(property)"><i class="fas fa-trash-restore"></i></button>
                                                    </template>
                                                    <template x-if="!group.isDeletedProperty(property)">
                                                        <button title="Remove property" class="fixed propertyControl imageOnly" :class="{visible: !group.isDeletedProperty(property)}" @click="group.removeProperty(property)"><i class="fas fa-trash"></i></button>
                                                    </template>
                                                </div>
                                            </div>
                                        </template>
                                    </template>
                                    
                                    <!-- Group actions-->    
                                    <div x-show="group.expanded" class="grid-item open-section group-actions">
                                        <button class="add-property-button" @click="addProperty(group)"><i class="fas fa-plus"></i> New property</button>
                                        
                                        <template x-if="group.properties.length > 0 && model.typeDefinition.propertyGroups.length > 1">
                                            <select class="leftArrow" @change="moveProperties(group, $event.target)">
                                                <option disabled selected value="">Move all properties</option>
                                                <template x-for="targetGroup in model.typeDefinition.propertyGroups.filter(g => g.name != group.name)">
                                                    <option :value="targetGroup.name" x-text="targetGroup.displayName"></option>
                                                </template>
                                            </select>
                                        </template>
                                        
                                        <template x-if="group.properties.length > 0">
                                            <button class="delete-properties-button" @click="group.removeProperties()"><i class="fas fa-trash"></i>Delete all properties</button>
                                        </template>
                                         
                                    </div>
                                    
                                    <div class="open-section property-group-spacer"></div>
                                </div>
                            </template>
                            
                            <div class="grid-item bottom-controls-section open-section">
                                <template x-if="td.propertyGroups.length > 0">
                                    <div class="add-group-section">
                                        <button class="add-group-button" @click="td.addPropertyGroup()"><i class="fas fa-plus"></i> New property group</button>
                                    </div>
                                </template>             
                            </div>
                            
                        </div>
                    </template>
                   
                    
                    <!-- Links header -->
                    <div class="sectionHeader">
                        <button :title="td.linksSection.expanded ? 'Collapse' : 'Expand'" 
                                class="imageOnly" 
                                @click="td.linksSection.toggle()">
                            <i :class="td.linksSection.expanded ? 'fas fa-caret-up' : 'fas fa-caret-down'"></i>
                        </button>
                        <div class="sectionName">Links</div>
                    </div>
                    
                    <template x-if="td.linksSection.expanded">
                        <div class="editorSection">HI THERE I'M LINKS</div>
                    </template>
                    
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
        this.name = name;
        this.type = type;
        this.description = description;
    }
}

class PropertyGroup {
    id;
    name;
    isNamedGroup = true;
    properties;

    constructor(obj) {
        Object.assign(this, obj);
        this.properties = this.properties.map((field, index, array) => {
            return new Property(field.id, field.name, field.type, field.description);
        });
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
    links;

    constructor(obj) {
        this.name = new EditablePropertyField(obj.name, obj.name);
        this.description = new EditablePropertyField(obj.description, obj.description);
        this.propertyGroups = obj.propertyGroups.map((group, index, array) => {
            return new PropertyGroup(group);
        });
        this.links = new Links(obj.links);
        console.info("Created type definition", this);
    }

    get modified() {
        let definitionModified = this.name.modified || this.description.modified;
        let groupModified = this.propertyGroups.some(g => g.modified);
        return definitionModified || groupModified;
    }
}

class TypeDefinitionViewModel {
    _typeDefinition;
    propertiesSection;
    linksSection;
    constructor(typeDefinition) {
        this._typeDefinition = typeDefinition;
        this.propertiesSection = new TypeDefinitionPropertiesSectionViewModel(typeDefinition.propertyGroups);
        this.linksSection = new TypeDefinitionLinksSectionViewModel(typeDefinition.links);
    }

    get name() {
        return this._typeDefinition.name;
    }

    get description() {
        return this._typeDefinition.description;
    }

    get propertyGroups() {
        return this.propertiesSection.propertyGroups;
    }

    addPropertyGroup() {
        this.propertiesSection.addPropertyGroup();
    }
}

class AbstractTypeDefinitionSectionViewModel {
    expanded = true;

    toggle() {
        console.info("toggle expand/collapse");
        this.expanded = !this.expanded;
    }
}

class TypeDefinitionPropertiesSectionViewModel extends AbstractTypeDefinitionSectionViewModel {
    propertyGroups;
    constructor(propertyGroups) {
        super();
        this.propertyGroups = propertyGroups.map((group, index, array) => {
            return new PropertyGroupViewModel(group);
        });
        console.info("Set property groups", this.propertyGroups);
    }

    addPropertyGroup() {
        let group = new PropertyGroup({id: null, name: null, properties: []});
        let groupViewModel = new PropertyGroupViewModel(group);
        this.propertyGroups.push(groupViewModel);
    }

}

class TypeDefinitionLinksSectionViewModel extends AbstractTypeDefinitionSectionViewModel {
    _links;
    constructor(links) {
        super();
    }
}

class PropertyGroupViewModel {
    _propertyGroup;
    _propertyViewModels;
    expanded = true;

    addedProperties = [];
    removedProperties = [];
    deletedProperties = [];

    constructor(propertyGroup) {
        this._propertyGroup = propertyGroup;
        this._propertyViewModels = propertyGroup.properties.map((property, index, array) => {
            return new PropertyViewModel(property);
        });
    }

    toggle() {
        this.expanded = !this.expanded;
    }

    get id() {
        return this._propertyGroup.id;
    }

    set name(value) {
        if (value == null || value.trim() === "") {
            this._propertyGroup.name = null;
        } else {
            this._propertyGroup.name = value;
        }

    }

    get name() {
        return this._propertyGroup.isNamedGroup ? this._propertyGroup.name : "(No group)";
    }

    isNameEditable() {
        return this._propertyGroup.isNamedGroup;
    }

    get properties() {
        return this._propertyViewModels;
    }

    addProperty() {
        let newProperty = new Property(null, null, null, null);
        let newPropertyViewModel = new PropertyViewModel(newProperty);
        this._propertyViewModels.push(newPropertyViewModel);
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

    removeProperty(prop) {
        if (this.isNewProperty(prop)) {
            this._propertyViewModels.splice(this._propertyViewModels.indexOf(prop), 1);
        } else {
            console.info("Pushing deleted prop", prop);
            this.deletedProperties.push(prop);
        }
    }

    removeProperties() {
        for (const propertyView of this._propertyViewModels) {
            this.removeProperty(property);
        }
    }

    unremoveProperty(prop) {
        let idx = this.deletedProperties.indexOf(prop);
        if (idx >= 0) {
            this.deletedProperties.splice(idx, 1);
        }
    }

    isNewProperty(property) {
        return property.id == null;
    }

    isNormalProperty(property) {
        return !this.isNewProperty(property) && !this.isDeletedProperty(property);
    }

    isDeletedProperty(property) {
        return this.deletedProperties.includes(property);
    }

    isDeletable() {
        let isUnnamedGroup = !this._propertyGroup.isNamedGroup;
        let undeletedProperties = this._propertyViewModels.filter(p => !this.isDeletedProperty(p));
        return !isUnnamedGroup && undeletedProperties.length === 0;
    }

    // TODO: delete group via the propertygroupsection view model
    isDeleted() {
        return this._propertyGroup.deleted;
    }

    delete() {
        this._propertyGroup.deleted = true;
    }

    undelete() {
        this._propertyGroup.deleted = false;
    }
}

class PropertyViewModel {
    _property;

    name;
    type;
    description;

    constructor(property) {
        this._property = property;

        this.name = new EditablePropertyField("name", property.name);
        this.type = new EditablePropertyField("type", property.type);
        this.description = new EditablePropertyField("description", property.description);
    }

    get id() {
        return this._property.id;
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

class EditablePropertyField {
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