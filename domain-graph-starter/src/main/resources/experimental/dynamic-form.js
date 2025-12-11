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
                    
                    &:hover {
                       border: solid 1px #ccc;
                    }
                }

                select {
                    padding: 5px;
                    appearance: none;
                    border: none;
                   
                    &:hover {
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
            <div class="dynamic-form" :class="form.theme">
                <form @submit.prevent="form.handleSubmit">
                    <!-- Form header -->    
                    <div class="form-header">
                        <template x-for="field in form.data.fields">
                            <div class="grid-item" x-text="field.label"></div>
                        </template>
                        <div class="grid-item">Actions</div>
                    </div>
                    
                    <!-- Form rows -->
                    <template x-for="item in form.data.items">
                        <div style="display:contents">
                            <template x-for="field in form.data.fields">
                                <div class="grid-item">
                                    <template x-if="field.type === 'String' && !field.options">
                                        <input type="text" x-model="item[field.itemField]">
                                    </template>
                                    <template x-if="field.type === 'String' && field.options">
                                        <select x-model="item[field.itemField]" @change="console.log('Change event:', item)">
                                            <template x-for="option in field.options">
                                                <option :value="option" x-text="option" :selected="option == item[field.itemField]"></option>
                                            </template>
                                        </select> 
                                    </template>
                                </div>
                            </template>
                            <div class="grid-item">
                                <button @click="form.removeField(item)">Remove</button>
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