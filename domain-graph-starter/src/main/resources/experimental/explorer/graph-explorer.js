import { Graph, Node } from "./explorer-model.js"
import { GraphView } from "./explorer-view.js"

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
            this.startAddingNodes();
        });
    }

    setupView() {
        const thisElement = this.$el;
        thisElement.style.display = "flex";
        thisElement.style.flexDirection = "row";
        thisElement.style.fontFamily = "sans-serif";

        const svgElement = thisElement.querySelector("svg");
        const graph = new Graph();
        this.graph = graph;
        this.graphView = new GraphView(graph);
        this.graphView.init(svgElement);
    }

    startAddingNodes() {
        let count = 0;
        const maxCount = 20; // Add 5 nodes over 10 seconds

        const interval = setInterval(() => {
            count++;

            // Generate random photographer name
            const names = ["Alice Smith", "Bob Wilson", "Carol Davis", "Dave Martinez", "Eve Brown"];
            const randomName = names[Math.floor(Math.random() * names.length)] + " " + count;

            const newNode = new Node("Photographer", randomName);
            this.graph.addNode(newNode);

            console.log(`Added photographer: ${randomName}`);

            // Stop after 10 seconds (5 nodes)
            if (count >= maxCount) {
                clearInterval(interval);
                console.log("Stopped adding nodes");
            }
        }, 2000); // Every 2 seconds
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