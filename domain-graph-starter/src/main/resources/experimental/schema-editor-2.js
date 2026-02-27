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
                background-color: var(--grid-border-color);
                
                display: grid;
                grid-template-columns: min-content min-content 2fr min-content;
                align-items: center;
                grid-gap: 1px;
                
                .property-group {
                    grid-column: 1 / span 4;
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
                <div class="schema-editor" x-data="{model, get td() { return model.typeDefinition; }}">
                    <!-- Type definition header -->
                    <div class="schema-header">
                        <input type="text" class="schema-type-name" x-model.lazy="td.name.value">
                        <input type="text" class="schema-type-description" x-model.lazy="td.description.value">
                    </div>
                    
                    <!-- Properties header -->
                    <div class="sectionHeader" x-data="{propSec: td.propertiesSection}">
                        <template x-if="propSec.expanded">
                            <button title="Collapse" class="imageOnly" @click="propSec.toggle()"><i class="fas fa-caret-up"></i></button>
                        </template>
                        <template x-if="!propSec.expanded">
                            <button title="Expand" class="imageOnly" @click="propSec.toggle()"><i class="fas fa-caret-down"></i></button>
                        </template>
                        <div class="sectionName">Properties</div>
                    </div>
                    
                    <template x-if="td.propertiesSection.expanded">
                        <div class="propertyEditorSection">
                            <template x-for="group in td.propertiesSection.propertyGroups">
                                <div style="display:contents">
                               
                                    <div class="grid-item property-group">
                                        <input placeholder="Enter a property group name" type="text" class="group-name-input" :class="{pending: group.name == null}" x-model.lazy="group.name">
                                    </div>
                                    
                                    <!-- Column header -->    
                                    <template>
                                        <div class="form-header">
                                            <template x-for="column in model.propertySchema.columns">
                                                <div class="grid-item" x-text="column"></div>
                                            </template>
                                        </div>
                                    </template>
                                
                                </div>
                                
                            </template>
                        </div>
                    </template>
                   
                    
                    <!-- Links header -->
                    <div class="sectionHeader" x-data="{linksSec: td.linksSection}">
                        <template x-if="linksSec.expanded">
                            <button title="Collapse" class="imageOnly" @click="linksSec.toggle()"><i class="fas fa-caret-up"></i></button>
                        </template>
                        <template x-if="!linksSec.expanded">
                            <button title="Expand" class="imageOnly" @click="linksSec.toggle()"><i class="fas fa-caret-down"></i></button>
                        </template>
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
    links;

    constructor(obj) {
        this.name = new EditableProperty(obj.name, obj.name);
        this.description = new EditableProperty(obj.description, obj.description);
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
}

class AbstractTypeDefinitionSectionViewModel {
    expanded = true;

    toggle() {
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
}

class TypeDefinitionLinksSectionViewModel extends AbstractTypeDefinitionSectionViewModel {
    _links;
    constructor(links) {
        super();
    }
}

class PropertyGroupViewModel {
    _propertyGroup;
    constructor(propertyGroup) {
        this._propertyGroup = propertyGroup;
    }
    get id() {
        return this._propertyGroup.id;
    }
    get name() {
        return this._propertyGroup.name;
    }
}