const DynamicFormComponent = (userOptions = {}) => {

    // ========== CSS STYLES ==========
    const styles = `
        .dynamic-form {
            font-family: sans-serif;
            position: relative;
            
            form {
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
            }
            
            button {
                cursor: pointer;
            }
        }
    `;

    // ========== HTML TEMPLATE ==========
    const template = `
        <div class="dynamic-form-contents" style="display:contents" x-data="{form}">
           
            <div class="dynamic-form">
                
                <form @submit.prevent="form.handleSubmit">
                    <!-- Form header -->    
                    <div class="form-header">
                        <template x-for="column in form.data.columns">
                            <div class="grid-item" x-text="column"></div>
                        </template>
                    </div>
                    
                    <!-- Form rows -->
                    <template x-for="(property, index) in form.data.properties">
                        <div style="display:contents">
                            <div class="grid-item" :class="{new: form.isNew(property), modified: form.isNormal(property) && property.name.modified, deleted: form.isDeleted(property)}">
                                <input type="text" class="name-input" x-model.lazy="property.name.value">
                            </div>
                            <div class="grid-item" :class="{new: form.isNew(property), modified: form.isNormal(property) && property.type.modified, deleted: form.isDeleted(property)}">
                                <template x-if="property.id == null">
                                    <select x-model.lazy="property.type.value">
                                        <template x-for="type in form.data.propertyTypes">
                                            <option :value="type" x-text="type" :selected="type == property.type.value"></option>
                                        </template>
                                    </select>
                                </template>
                                <template x-if="property.id != null">
                                    <div x-text="property.type.value"></div>
                                </template>        
                            </div>
                            <div class="grid-item" :class="{new: form.isNew(property), modified: form.isNormal(property) && property.description.modified, deleted: form.isDeleted(property)}">
                                <input type="text" x-model.lazy="property.description.value">
                            </div>
                            <div class="grid-item" :class="{new: form.isNew(property), deleted: form.isDeleted(property)}">
                                <template x-if="form.isDeleted(property)">
                                    <button @click="form.unremoveProperty(property)">Restore</button>
                                </template>
                                <template x-if="form.isNormal(property) && property.modified">
                                    <button @click="property.clearChanges()">Clear changes</button>
                                </template>
                                <template x-if="!form.isDeleted(property)">
                                    <button @click="form.removeProperty(property)">Remove</button>
                                </template>
                            </div>
                        </div>
                    </template>
                    
                    <div class="grid-item" style="grid-column: 1 / span 4">
                        <button class="addPropertyButton" @click="addProperty()">New property</button>
                    </div>
                </form>
               
            </div>
            
        </div>
    `;

    // ========== JAVASCRIPT LOGIC ==========

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
        id;
        name;
        type;
        description;

        constructor(id, name, type, description) {
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

    let properties = userOptions.data.items.map((field, index, array) => {
        return new Property(field.id, field.name, field.type, field.description);
    });
    console.info("Got properties", properties);

    userOptions.data.properties = properties;

    return {
        // Configuration
        form: {
            data: userOptions.data,
            deletedProperties: [],
            state: {
                isAdding: false
            },
            addProperty() {
                let newProperty = new Property(null, null, null, null);
                this.data.properties.push(newProperty);
            },
            removeProperty(prop) {
                if (this.isNew(prop)) {
                    this.data.properties.splice(this.data.properties.indexOf(prop), 1);
                } else {
                    this.deletedProperties.push(prop);
                }
            },
            unremoveProperty(prop) {
                let idx = this.deletedProperties.indexOf(prop);
                if (idx >= 0) {
                    this.deletedProperties.splice(idx, 1);
                }
            },
            isNew(property) {
                return property.id == null;
            },
            isNormal(property) {
                return !this.isNew(property) && !this.isDeleted(property);
            },
            isDeleted(property) {
              return this.deletedProperties.includes(property);
            },
            handleSubmit(evt) {
                evt.preventDefault();
            }
        },

        // Lifecycle hooks
        init() {
            // Inject styles
            this.injectStyles();

            // Render template
            this.renderTemplate();

            // Dispatch event
            this.$dispatch('dynamic-form:init', { data: this.form });
        },

        // Methods
        injectStyles() {
            const styleId = 'form-builder-styles';
            if (!document.getElementById(styleId)) {
                const style = document.createElement('style');
                style.id = styleId;
                style.textContent = styles;
                document.head.appendChild(style);
            }
        },

        // Add this method to render the template
        renderTemplate() {
            if (this.$el) {
                this.$el.innerHTML = template;
            }
        },

        addProperty() {
            this.form.addProperty();
            let newIndex = this.form.data.properties.length - 1;
            this.$nextTick(() => {
                console.info("tock", this);
                const nameInputs = document.querySelectorAll('.dynamic-form .name-input');

                if (nameInputs.length > 0) {
                    const lastInput = nameInputs[nameInputs.length - 1];
                    lastInput.focus();
                    lastInput.select();

                    // Optional: Scroll into view
                    lastInput.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                }
            });
        },

        destroy() {
            // Cleanup if needed
            this.$dispatch('dynamic-form:destroy');
        }
    };

};

// ========== AUTO-REGISTRATION ==========
// Register with Alpine if available
if (typeof Alpine !== 'undefined') {
    Alpine.data('DynamicFormComponent', DynamicFormComponent);
}

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = DynamicFormComponent;
} else if (typeof define === 'function' && define.amd) {
    define([], () => DynamicFormComponent);
} else {
    window.DynamicFormComponent = DynamicFormComponent;
}