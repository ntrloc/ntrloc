export class NodeView {
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

export class LinkView {
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

export class GraphView {

    constructor(graph) {
        this.graph = graph;
        this.simulation = null;
        this.d3Ready = this.loadD3();

        const nodeViewMap = new Map();

        graph.nodes.forEach(node => {
            const nodeView = new NodeView(node);
            nodeViewMap.set(node, nodeView);
        });

        const linkViews = graph.links.map(link => {
            const sourceView = nodeViewMap.get(link.sourceNode);
            const targetView = nodeViewMap.get(link.targetNode);
            return new LinkView(link, sourceView, targetView);
        });

        this.nodeViews = Array.from(nodeViewMap.values());
        this.linkViews = linkViews;
    }

    async init(svgElement) {
        await this.d3Ready;

        this.svgElement = svgElement;
        this.svg = this.d3.select(svgElement);

        const rect = svgElement.getBoundingClientRect();
        const centerX = rect.width / 2;
        const centerY = rect.height / 2;

        this.setupArrowMarker();
        this.setupSimulation(centerX, centerY);
        this.render();
    }

    loadD3() {
        return new Promise((resolve, reject) => {
            if (window.d3) {
                this.d3 = window.d3;
                resolve(window.d3);
                return;
            }

            if (document.querySelector('script[src="./d3.v7.js"]')) {
                const checkD3 = setInterval(() => {
                    if (window.d3) {
                        clearInterval(checkD3);
                        this.d3 = window.d3;
                        resolve(window.d3);
                    }
                }, 50);
                return;
            }

            const script = document.createElement('script');
            script.src = './d3.v7.js';
            script.onload = () => {
                this.d3 = window.d3;
                resolve(window.d3);
            };
            script.onerror = () => reject(new Error('Failed to load d3'));
            document.head.appendChild(script);
        });
    }

    setupArrowMarker() {
        this.svg.append("defs").append("marker")
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
    }

    setupSimulation() {
        this.simulation = this.d3.forceSimulation(this.nodeViews)
            .force("link", d3.forceLink(this.linkViews).id(d => d.id).strength(2).distance(200))
            .force("charge", d3.forceManyBody().strength(-300));

        this.simulation.on("tick", () => {
            if (this.d3Links) {
                this.d3Links.each(function (d) {
                    const sourceEdge = d.sourceView.getEdgePoint(d.targetView.centerX, d.targetView.centerY);
                    const targetEdge = d.targetView.getEdgePoint(d.sourceView.centerX, d.sourceView.centerY);

                    d3.select(this)
                        .attr("x1", sourceEdge.x)
                        .attr("y1", sourceEdge.y)
                        .attr("x2", targetEdge.x)
                        .attr("y2", targetEdge.y);
                });
            }
            if (this.d3Nodes) {
                this.d3Nodes.attr("transform", d => `translate(${d.x},${d.y})`);
            }

        });

        window.addEventListener('resize', () => this.centerSimulation());

        this.centerSimulation();
    }

    render() {
        // draw links and boxes
        const nodeGroup = this.svg.selectAll("g.node")
            .data(this.nodeViews)
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

        this.d3Nodes = nodeGroup;

        const links = this.svg.selectAll("line")
            .data(this.linkViews)
            .join("line")
            .attr("stroke", "red")
            .attr("stroke-width", 2)
            .attr("marker-end", "url(#arrowhead)");
        this.d3Links = links;

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
    }

    centerSimulation() {
        const rect = this.svgElement.getBoundingClientRect();
        const centerX = rect.width / 2;
        const centerY = rect.height / 2;

        this.simulation.force("center", this.d3.forceCenter(centerX, centerY));
        this.simulation.alpha(0.3).restart();
    }

}