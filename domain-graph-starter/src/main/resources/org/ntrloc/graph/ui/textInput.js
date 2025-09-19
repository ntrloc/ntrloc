$.widget("ntrloc.textInput", {

    inputClassName: "ntrloc-textInput",

    // Default options for the widget
    options: {
        label: "Input label"
    },

    // The _create method is called when the widget is initialized
    _create: function() {
        this.element.addClass(this.inputClassName);
        this.element.append(`<div class='label'>${this.options.label}</div>`);
        this.element.append("<input class='input' type='text' />");
    },

    // The _setOption method is called when an option is changed
    _setOption: function(key, value) {
        this._super(key, value); // Call the parent _setOption
        if (key === "label") {
            this.element.text(value);
        } else if (key === "color") {
            this.element.css("color", value);
        }
    },

    // The _destroy method is called when the widget is destroyed
    _destroy: function() {
        this.element.removeClass(this.inputClassName);
        this.element.text("");
        this.element.css("color", "");
    }
});