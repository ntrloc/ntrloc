export class NodeView {
    constructor(node, headerColor = "#4a90e2") {
        this.node = node;
        this.x = 0;
        this.y = 0;
        this.width = 0;
        this.height = 0;

        this.headerPadding = {
            horizontal: 7,
            vertical: 7
        };
        this.bodyPadding = {
            horizontal: 10,
            vertical: 5
        };

        this.headerColor = headerColor;
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

        this.nodeViews = [];
        this.linkViews = [];

        this.createViews();

        this.graph.addChangeListener(() => this.onModelChanged());
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
        const rect = this.svgElement.getBoundingClientRect();
        const centerX = rect.width / 2;
        const centerY = rect.height / 2;

        this.simulation = this.d3.forceSimulation(this.nodeViews)
            .force("link", this.d3.forceLink(this.linkViews).id(d => d.id).strength(2).distance(200))
            .force("charge", this.d3.forceManyBody().strength(-50))
            .force("x", this.d3.forceX(centerX).strength(0.1))  // Pull each node toward center X
            .force("y", this.d3.forceY(centerY).strength(0.1))
            .force("collide", d3.forceCollide().radius(d => Math.max(d.width, d.height) / 2 + 30).strength(1));

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

    createViews() {
        const nodeTypeColors = new Map([
            ["Photographer", "#6a4c93"],
            ["Photo", "#4a90e2"]
        ]);

        // Step 1: Find nodes that were added or removed
        const existingNodeViewMap = new Map();
        this.nodeViews.forEach(nv => existingNodeViewMap.set(nv.node, nv));

        const oldNodes = new Set(this.nodeViews.map(nv => nv.node));
        const newNodes = new Set(this.graph.nodes);

        const addedNodes = this.graph.nodes.filter(node => !oldNodes.has(node));
        const removedNodes = Array.from(oldNodes).filter(node => !newNodes.has(node));

        // Step 2: Remove NodeViews for removed nodes
        removedNodes.forEach(node => {
            existingNodeViewMap.delete(node);
        });

        // Step 3: Create new NodeViews for added nodes
        const rect = this.svgElement ? this.svgElement.getBoundingClientRect() : null;
        const centerX = rect ? rect.width / 2 : 400;
        const centerY = rect ? rect.height / 2 : 300;

        addedNodes.forEach(node => {
            const headerColor = nodeTypeColors.get(node.nodeType) || "#4a90e2";
            const nodeView = new NodeView(node, headerColor);

            // Position new nodes near the center
            nodeView.x = centerX + (Math.random() - 0.5) * 100;
            nodeView.y = centerY + (Math.random() - 0.5) * 100;

            existingNodeViewMap.set(node, nodeView);
        });

        // Step 4: Build the final nodeViewMap from graph.nodes (preserves order)
        const nodeViewMap = new Map();
        this.graph.nodes.forEach(node => {
            const nodeView = existingNodeViewMap.get(node);
            if (nodeView) {
                nodeViewMap.set(node, nodeView);
            }
        });

        // Step 5: Find links that were added or removed
        const existingLinkViews = this.linkViews;
        const oldLinks = new Set(existingLinkViews.map(lv => lv.link));
        const newLinks = new Set(this.graph.links);

        const addedLinks = this.graph.links.filter(link => !oldLinks.has(link));
        const removedLinks = existingLinkViews.filter(lv => !newLinks.has(lv.link));

        // Step 6: Keep existing LinkViews that are still valid
        const keptLinkViews = existingLinkViews.filter(lv => newLinks.has(lv.link));

        // Step 7: Create LinkViews for new links
        const newLinkViews = addedLinks.map(link => {
            const sourceView = nodeViewMap.get(link.sourceNode);
            const targetView = nodeViewMap.get(link.targetNode);

            if (sourceView && targetView) {
                return new LinkView(link, sourceView, targetView);
            }
            return null;
        }).filter(lv => lv !== null);

        // Step 8: Update the arrays
        this.nodeViews = Array.from(nodeViewMap.values());
        this.linkViews = [...keptLinkViews, ...newLinkViews];
    }

    onModelChanged() {
        if (this.svg) {
            this.createViews();
            this.render();
            if (this.simulation) {
                // Update the simulation with new data
                this.simulation.nodes(this.nodeViews);
                this.simulation.force("link").links(this.linkViews);
                this.centerSimulation();
            }
        }
    }

    render() {
        this.svg.selectAll("g.node").remove();
        this.svg.selectAll("line").remove();


        // Create node groups
        const nodeGroup = this.svg.selectAll("g.node")
            .data(this.nodeViews)
            .join("g")
            .attr("class", "node")
            .attr("cursor", "pointer")
            .call(drag(this.simulation));

        // Step 1: Add text elements first (so we can measure them)

        // Add node type text
        nodeGroup.append("text")
            .attr("class", "node-type-text")
            .attr("x", 0)  // Position will be set after measuring
            .attr("y", 0)  // Position will be set after measuring
            .attr("text-anchor", "middle")  // Center horizontally
            .attr("dominant-baseline", "middle")  // Center vertically
            .attr("fill", "white")
            .attr("font-weight", "bold")
            .attr("pointer-events", "none")
            .text(d => d.node.nodeType);

        // Add name text
        nodeGroup.append("text")
            .attr("class", "node-name-text")
            .attr("text-anchor", "start")
            .attr("dominant-baseline", "middle")
            .attr("fill", "black")
            .attr("pointer-events", "none")
            .text(d => d.node.name);

        // Step 2: Measure text and update NodeView dimensions
        nodeGroup.each(function(d) {
            const typeText = d3.select(this).select(".node-type-text");
            const nameText = d3.select(this).select(".node-name-text");

            const typeBBox = typeText.node().getBBox();
            const nameBBox = nameText.node().getBBox();

            // Calculate dimensions with horizontal padding (left + right)
            const headerWidth = typeBBox.width + (d.headerPadding.horizontal * 2);
            const bodyWidth = nameBBox.width + (d.bodyPadding.horizontal * 2);
            d.width = Math.max(headerWidth, bodyWidth, 80);

            // Calculate heights with vertical padding (top + bottom)
            d.headerHeight = typeBBox.height + (d.headerPadding.vertical * 2);
            d.bodyHeight = nameBBox.height + (d.bodyPadding.vertical * 2);
            d.height = d.headerHeight + d.bodyHeight;

            // Position the header text at the center of the header
            typeText
                .attr("x", d.width / 2)
                .attr("y", d.headerHeight / 2);

            // Position the name text (left-aligned with horizontal padding)
            nameText
                .attr("x", d.bodyPadding.horizontal)
                .attr("y", d.headerHeight + d.bodyPadding.vertical + nameBBox.height / 2);
        });

        // Step 3: Add rectangles (insert before text so they appear behind)

        // Header rectangle
        nodeGroup.insert("rect", ".node-type-text")
            .attr("class", "node-type-rect")
            .attr("width", d => d.width)
            .attr("height", d => d.headerHeight)
            .attr("x", 0)
            .attr("y", 0)
            .attr("fill", d => d.headerColor)
            .attr("stroke", "#95a6bf")
            .attr("stroke-width", 2);

        // Body rectangle
        nodeGroup.insert("rect", ".node-name-text")
            .attr("class", "node-name-rect")
            .attr("width", d => d.width)
            .attr("height", d => d.bodyHeight)
            .attr("x", 0)
            .attr("y", d => d.headerHeight)
            .attr("fill", "white")
            .attr("stroke", "#95a6bf")
            .attr("stroke-width", 2);

        this.d3Nodes = nodeGroup;

        const links = this.svg.selectAll("line")
            .data(this.linkViews)
            .join("line")
            .attr("stroke", "red")
            .attr("stroke-width", 2)
            .attr("marker-end", "url(#arrowhead)");
        this.d3Links = links;

        function drag(simulation) {
            const centerForce = simulation.force("center");
            const originalStrength = centerForce.strength();

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

                // Temporarily boost center force
                centerForce.strength(0.5);
                simulation.alpha(0.8).restart();

                // Reset center force strength after a delay
                setTimeout(() => {
                    centerForce.strength(originalStrength);
                }, 1000);
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

        this.simulation
            .force("center", this.d3.forceCenter(centerX, centerY).strength(0.6))
            .force("x", this.d3.forceX(centerX).strength(0.1))  // Pull each node toward center X
            .force("y", this.d3.forceY(centerY).strength(0.1));
        this.simulation.alpha(0.3).restart();
    }

}