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
            }
        }
    `;

    // ========== HTML TEMPLATE ==========
    const template = `
        <div class="dynamic-form-contents" style="display:contents" x-data="{form}">
            <div class="dynamic-form" :class="form.theme">
                <form @submit.prevent="handleSubmit">
                    <!-- Form header -->    
                    <div class="form-header">
                        <template x-for="field in form.data.fields">
                            <div class="grid-item" x-text="field.label"></div>
                        </template>
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
                        </div>
                    </template>
                    
                </form>
                
                
            </div>
            
            <!-- Add Field Prompt -->
            <!--
            <div id="fieldPrompt">
                <button @click="form.addField()" :disabled="{!form.newName.trim()}">Add</button>
                <div>
                    <input type="text" x-model="form.newName" @keydown.enter="addField()"
                            @keydown.escape="form.isAdding = false; form.newName = ''" 
                            placeholder="Enter field name" autofocus>
                    
                    <button @click="form.isAdding = false; form.newName = ''" >Cancel</button>
                </div>
            </div>
            -->
        </div>
    `;

    // ========== JAVASCRIPT LOGIC ==========
    return {
        // Configuration
        form: {
            data: userOptions.data,
            isAdding: false,
            newName: ''
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