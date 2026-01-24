import {Node, Link, Graph} from "./explorer-model.js"
import { NodeView, LinkView, GraphView } from "./explorer-view.js"

export class GraphExplorer {

    constructor() {
        this.stylesId = 'graph-explorer-styles';
        if (!document.getElementById(this.stylesId)) {
            this.injectStyles();
        }
    }

    async init() {
        this.$nextTick(() => {
            this.setupView();
        });
    }

    setupView() {
        const thisElement = this.$el;
        thisElement.style.display = "flex";
        thisElement.style.flexDirection = "row";
        thisElement.style.fontFamily = "sans-serif";

        const svgElement = thisElement.querySelector("svg");
        const graph = new Graph();
        this.graphView = new GraphView(graph);
        this.graphView.init(svgElement);
    }

    injectStyles() {
        const styleEl = document.createElement('style');
        styleEl.id = this.stylesId;
        styleEl.textContent = `
            .control-panel {
                background-color: #30363d;
                flex: 0.3;
                
                .control-panel-header {
                    background-color: #1a222b;
                    color: #95a6bf;
                    font-size: 1.1em;
                    font-weight: bold;
                    padding: 10px;
                    text-align: center;
                }
            }
            svg {
                flex: 0.7;
                background-color: #0f1419;
            }
        `;
        document.head.appendChild(styleEl);
    }

    getTemplate() {
        return `
            <div class="control-panel">
                <div>
                    <div class="control-panel-header">Working sets</div>
                    <div id="working-sets"></div>
                </div>
            </div>
            <svg></svg>
        `;
    }

}