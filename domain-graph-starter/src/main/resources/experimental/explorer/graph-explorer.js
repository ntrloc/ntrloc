import { Node, Link } from "./explorer-model.js"
import { NodeView, LinkView } from "./explorer-view.js"

export class GraphExplorer {

    constructor() {
        this.stylesId = 'graph-explorer-styles';
        this.d3Ready = this.loadD3();
        this.simulation = null;

        if (!document.getElementById(this.stylesId)) {
            this.injectStyles();
        }
    }

    async init() {
        const node1 = new Node("Photographer", 'Bill Jones');
        const node2 = new Node("Photo", "photo1.jpg");
        const node3 = new Node("Photo", "photo2.jpg");
        const link1 = new Link(node1, node2);
        const link2 = new Link(node1, node3);

        const nodeView1 = new NodeView(node1);
        const nodeView2 = new NodeView(node2);
        const nodeView3 = new NodeView(node3);
        const linkView1 = new LinkView(link1, nodeView1, nodeView2);
        const linkView2 = new LinkView(link2, nodeView1, nodeView3);

        const nodeViews = [nodeView1, nodeView2, nodeView3];
        const linkViews = [linkView1, linkView2];

        const d3 = await this.d3Ready;

        this.$nextTick(() => {

            const thisElement = this.$el;
            thisElement.style.display = "flex";
            thisElement.style.flexDirection = "row";
            thisElement.style.fontFamily = "sans-serif";

            const sel = thisElement.querySelector("svg");
            const svg = d3.select(sel);

            // Create the force simulation
            this.simulation = d3.forceSimulation(nodeViews)
                .force("link", d3.forceLink(linkViews).id(d => d.id).strength(2).distance(200))
                .force("charge", d3.forceManyBody().strength(-300));
            this.centerSimulation();

            svg.append("defs").append("marker")
                .attr("id", "arrowhead")
                .attr("viewBox", "0 0 10 10")
                .attr("refX", 9)  // Position at the end of the line
                .attr("refY", 5)
                .attr("markerWidth", 6)
                .attr("markerHeight", 6)
                .attr("orient", "auto")
                .append("path")
                .attr("d", "M 0 0 L 10 5 L 0 10 z")  // Triangle shape
                .attr("fill", "red");

            // draw links and boxes
            const nodeGroup = svg.selectAll("g.node")
                .data(nodeViews)
                .join("g")
                .attr("class", "node")
                .attr("cursor", "pointer")
                .call(drag(this.simulation));

            nodeGroup.append("rect")
                .attr("class", "node-type-rect")
                .attr("width", d => d.width)
                .attr("height", 20)  // Height for the type section
                .attr("x", 0)
                .attr("y", 0)
                .attr("fill", "#4a90e2")  // Colored background
                .attr("stroke", "#95a6bf")
                .attr("stroke-width", 2);

            // Add the node type text
            nodeGroup.append("text")
                .attr("class", "node-type-text")
                .attr("x", d => d.width / 2)
                .attr("y", 10)  // Center in the 20px high rectangle
                .attr("text-anchor", "middle")
                .attr("dominant-baseline", "middle")
                .attr("fill", "white")
                .attr("font-weight", "bold")
                .attr("pointer-events", "none")
                .text(d => d.node.nodeType);

            // Add the name rectangle (white, below)
            nodeGroup.append("rect")
                .attr("class", "node-name-rect")
                .attr("width", d => d.width)
                .attr("height", d => d.height - 20)  // Remaining height
                .attr("x", 0)
                .attr("y", 20)  // Start below the type rectangle
                .attr("fill", "white")
                .attr("stroke", "#95a6bf")
                .attr("stroke-width", 2);

            // Add the name text
            nodeGroup.append("text")
                .attr("class", "node-name-text")
                .attr("x", d => d.width / 2)
                .attr("y", d => 20 + (d.height - 20) / 2)  // Center in remaining space
                .attr("text-anchor", "middle")
                .attr("dominant-baseline", "middle")
                .attr("fill", "black")
                .attr("pointer-events", "none")
                .text(d => d.node.name);

            const link = svg.selectAll("line")
                .data(linkViews)
                .join("line")
                .attr("stroke", "red")
                .attr("stroke-width", 2)
                .attr("marker-end", "url(#arrowhead)");

            this.simulation.on("tick", () => {
                link.each(function(d) {
                    const sourceEdge = d.sourceView.getEdgePoint(d.targetView.centerX, d.targetView.centerY);
                    const targetEdge = d.targetView.getEdgePoint(d.sourceView.centerX, d.sourceView.centerY);

                    d3.select(this)
                        .attr("x1", sourceEdge.x)
                        .attr("y1", sourceEdge.y)
                        .attr("x2", targetEdge.x)
                        .attr("y2", targetEdge.y);
                });
                nodeGroup.attr("transform", d => `translate(${d.x},${d.y})`);
            });

            function drag(simulation) {
                function dragstarted(event, d) {
                    if (!event.active) simulation.alphaTarget(0.3).restart();
                    d.fx = d.x;
                    d.fy = d.y;
                }

                function dragged(event, d) {
                    d.fx = event.x;
                    d.fy = event.y;
                }

                function dragended(event, d) {
                    if (!event.active) simulation.alphaTarget(0);
                    d.fx = null;
                    d.fy = null;
                }

                return d3.drag()
                    .on("start", dragstarted)
                    .on("drag", dragged)
                    .on("end", dragended);
            }

            window.addEventListener('resize', () => this.centerSimulation());

        });
    }

    centerSimulation() {
        const thisElement = this.$el;
        const sel = thisElement.querySelector("svg");
        const svgRect = sel.getBoundingClientRect();
        const centerX = svgRect.width / 2;
        const centerY = svgRect.height / 2;

        this.simulation.force("center", d3.forceCenter(centerX, centerY));
        this.simulation.alpha(0.3).restart();
        this.simulation.force("center", d3.forceCenter(centerX, centerY));
    }

    loadD3() {
        return new Promise((resolve, reject) => {
            // Check if d3 is already loaded
            if (window.d3) {
                resolve(window.d3);
                return;
            }

            // Check if script is already being loaded
            if (document.querySelector('script[src="./d3.v7.js"]')) {
                // Wait for it to load
                const checkD3 = setInterval(() => {
                    if (window.d3) {
                        clearInterval(checkD3);
                        resolve(window.d3);
                    }
                }, 50);
                return;
            }

            // Load the script
            const script = document.createElement('script');
            script.src = './d3.v7.js';
            script.onload = () => resolve(window.d3);
            script.onerror = () => reject(new Error('Failed to load d3'));
            document.head.appendChild(script);
        });
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