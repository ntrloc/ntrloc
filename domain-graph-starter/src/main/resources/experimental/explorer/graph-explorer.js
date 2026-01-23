export class GraphExplorer {

    constructor() {
        this.stylesId = 'graph-explorer-styles';
        this.d3Ready = this.loadD3();

        if (!document.getElementById(this.stylesId)) {
            this.injectStyles();
        }
    }

    async init() {
        const node1 = new Node('Node 1');
        const node2 = new Node('Node 2');
        const node3 = new Node("Node 3");
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
            const simulation = d3.forceSimulation(nodeViews)
                .force("link", d3.forceLink(linkViews).id(d => d.id).distance(200))
                .force("charge", d3.forceManyBody().strength(-300))
                .force("center", d3.forceCenter(200, 200));

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
                .call(drag(simulation));

            nodeGroup.append("rect")
                .attr("width", 80)
                .attr("height", 40)
                .attr("x", 0)  // Center the rect
                .attr("y", 0)
                .attr("stroke", "#95a6bf")
                .attr("stroke-width", 2)
                .attr("fill", "white");

            nodeGroup.append("text")
                .attr("x", d => d.width / 2)        // Center horizontally
                .attr("y", d => d.height / 2)       // Center vertically
                .attr("text-anchor", "middle")
                .attr("fill", "black")
                .attr("pointer-events", "none")
                .text(d => d.id);

            const link = svg.selectAll("line")
                .data(linkViews)
                .join("line")
                .attr("stroke", "red")
                .attr("stroke-width", 2)
                .attr("marker-end", "url(#arrowhead)");

            simulation.on("tick", () => {

                link.each(function(d) {
                    const sourceEdge = d.sourceView.getEdgePoint(d.targetView.centerX, d.targetView.centerY);
                    const targetEdge = d.targetView.getEdgePoint(d.sourceView.centerX, d.sourceView.centerY);

                    d3.select(this)
                        .attr("x1", sourceEdge.x)
                        .attr("y1", sourceEdge.y)
                        .attr("x2", targetEdge.x)
                        .attr("y2", targetEdge.y);
                });

                nodeGroup
                    .attr("transform", d => `translate(${d.x},${d.y})`);

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

        });
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

// node.js
class Node {
    constructor(name) {
        this.name = name;
    }
}

// node-view.js
class NodeView {
    constructor(node, x = 0, y = 0, width = 80, height = 40) {
        this.node = node;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    get id() {
        return this.node.name;
    }

    get centerX() {
        return this.x + (this.width / 2);
    }

    get centerY() {
        return this.y + (this.height / 2);
    }

    // given a line drawn from the center of this rectangle to the given x/y coordinates,
    // returns the x/y coordinates where that line intersects the boundary of this rectangle.
    getEdgePoint(targetX, targetY) {
        const dx = targetX - this.centerX;
        const dy = targetY - this.centerY;

        if (dx === 0 && dy === 0) {
            return { x: this.centerX, y: this.centerY };
        }

        const halfWidth = this.width / 2;
        const halfHeight = this.height / 2;

        let t = Infinity;

        if (dx > 0) {
            t = Math.min(t, halfWidth / dx);
        }
        if (dx < 0) {
            t = Math.min(t, -halfWidth / dx);
        }
        if (dy > 0) {
            t = Math.min(t, halfHeight / dy);
        }
        if (dy < 0) {
            t = Math.min(t, -halfHeight / dy);
        }

        return {
            x: this.centerX + t * dx,
            y: this.centerY + t * dy
        };
    }
}

// link.js
class Link {
    constructor(sourceNode, targetNode) {
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
    }
}

// link-view.js
class LinkView {
    constructor(link, sourceView, targetView) {
        this.link = link;
        this.sourceView = sourceView;
        this.targetView = targetView;
    }

    get x1() {
        return this.sourceView.x;
    }

    get y1() {
        return this.sourceView.y;
    }

    get x2() {
        return this.targetView.x;
    }

    get y2() {
        return this.targetView.y;
    }

    get source() {
        return this.sourceView;
    }

    get target() {
        return this.targetView;
    }
}