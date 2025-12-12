const DynamicFormComponent = (userOptions = {}) => {

    // ========== CSS STYLES ==========
    const styles = `
        .dynamic-form {
            font-family: sans-serif;
            font-weight: bold;
            
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
                    
                    &.modified {
                        background-color: #fff8b0;
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
                    padding: 5px;
                    border-radius: 3px;
                    border: 1px solid #666666;
                }
            }
            
            .add-field-form {
                background-color: yellow;
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
                    <template x-for="property in form.data.properties">
                        <div style="display:contents">
                            <div class="grid-item" :class="{modified: property.name.modified}">
                                <input type="text" x-model.lazy="property.name.value">
                            </div>
                            <div class="grid-item" :class="{modified: property.type.modified}">
                                <select x-model.lazy="property.type.value">
                                    <template x-for="type in form.data.propertyTypes">
                                        <option :value="type" x-text="type" :selected="type == property.type.value"></option>
                                    </template>
                                </select>        
                            </div>
                            <div class="grid-item" :class="{modified: property.description.modified}">
                                <input type="text" x-model.lazy="property.description.value">
                            </div>
                            <div class="grid-item">
                                <button @click="form.removeField(property)">Remove</button>
                            </div>
                        </div>
                    </template>
                </form>
                
                <div class="add-field-form" x-show="form.state.isAdding">
                 hi
                </div>
                
               
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
    }

    class Property {
        name;
        type;
        description;

        constructor(name, type, description) {
            this.name = new EditableProperty(name, name);
            this.type = new EditableProperty(name, type);
            this.description = new EditableProperty(name, description);
        }
    }

    let properties = userOptions.data.items.map((field, index, array) => {
        return new Property(field.name, field.type, field.description);
    });
    console.info("Got properties", properties);

    userOptions.data.properties = properties;

    return {
        // Configuration
        form: {
            data: userOptions.data,
            state: {
                isAdding: false,
                startAdding() {
                    this.isAdding = true;
                },
                commitAdding() {
                    this.isAdding = false;
                },
                cancelAdding() {
                    this.isAdding = false;
                }
            },
            removeField(field) {
                console.info("Remove field", field);
                let fieldIndex = this.data.items.indexOf(field);
                console.info("Remove field index", fieldIndex);
                this.data.items.splice(fieldIndex, 1);
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