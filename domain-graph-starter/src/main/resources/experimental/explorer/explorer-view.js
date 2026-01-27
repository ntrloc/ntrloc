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

        this.graph.addChangeListener(() => this.onModelChanged());
    }

    async init(svgElement) {
        await this.d3Ready;

        this.svgElement = svgElement;
        this.svg = this.d3.select(svgElement);
        this.container = this.svg.append("g");

        this.setupArrowMarker();
        this.createViews();
        this.render();

        const rect = svgElement.getBoundingClientRect();
        const centerX = rect.width / 2;
        const centerY = rect.height / 2;

        this.setupSimulation(centerX, centerY);

        const zoom = this.d3.zoom()
            .scaleExtent([0.1, 4])  // Allow zoom from 10% to 400%
            .on("zoom", (event) => {
                this.container.attr("transform", event.transform);
            });
        this.zoom = zoom;
        this.svg.call(zoom);
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
            .force("collide", d3.forceCollide()
                .radius(d => {
                    const radius = Math.max(d.width, d.height) * 0.7;
                    console.info(`Node group width: ${d.width}, height: ${d.height}, radius: ${radius}`);
                    return radius;
                })
                .strength(1).iterations(5))
            .force("link", this.d3.forceLink(this.linkViews).id(d => d.id).strength(0.5).distance(250))
            .force("x", this.d3.forceX(centerX).strength(0.05))  // Pull each node toward center X
            .force("y", this.d3.forceY(centerY).strength(0.05))
            .force("charge", this.d3.forceManyBody().strength(-50))
            ;

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

        this.simulation.on("end", () => { this.fitToView() });

        window.addEventListener('resize', () => this.onWindowResized());

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

    onWindowResized() {
        this.centerSimulation();
        this.fitToView();
    }

    render() {
        this.container.selectAll("g.node").remove();
        this.container.selectAll("line").remove();


        // Create node groups
        const nodeGroup = this.container.selectAll("g.node")
            .data(this.nodeViews)
            .join("g")
            .attr("class", "node")
            .attr("cursor", "pointer")
            .call(this.createDragBehavior());

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

            console.info(`Node group width: ${d.width}, height: ${d.height}`);
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

        const links = this.container.selectAll("line")
            .data(this.linkViews)
            .join("line")
            .attr("stroke", "red")
            .attr("stroke-width", 2)
            .attr("marker-end", "url(#arrowhead)");
        this.d3Links = links;
    }

    createDragBehavior() {
        const self = this;  // Capture reference to GraphView

        function dragstarted(event, d) {
            if (!self.simulation) return;  // Guard against undefined
            if (!event.active) self.simulation.alphaTarget(0.3).restart();
            d.fx = d.x;
            d.fy = d.y;
        }

        function dragged(event, d) {
            d.fx = event.x;
            d.fy = event.y;
        }

        function dragended(event, d) {
            if (!self.simulation) return;  // Guard against undefined
            if (!event.active) self.simulation.alphaTarget(0);
            d.fx = null;
            d.fy = null;

            if (self.simulation) {
                self.simulation.alpha(0.5).restart();
            }
        }

        return this.d3.drag()
            .on("start", dragstarted)
            .on("drag", dragged)
            .on("end", dragended);
    }

    centerSimulation() {
        const rect = this.svgElement.getBoundingClientRect();
        const centerX = rect.width / 2;
        const centerY = rect.height / 2;

        this.simulation
            //.force("center", this.d3.forceCenter(centerX, centerY).strength(0.6))
            .force("x", this.d3.forceX(centerX).strength(0.05))  // Pull each node toward center X
            .force("y", this.d3.forceY(centerY).strength(0.05));
        this.simulation.alpha(0.3).restart();
    }

    fitToView() {
        if (!this.nodeViews || this.nodeViews.length === 0) return;

        // Calculate bounding box of all nodes
        let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;

        this.nodeViews.forEach(nv => {
            const left = nv.x;
            const top = nv.y;
            const right = nv.x + nv.width;
            const bottom = nv.y + nv.height;

            minX = Math.min(minX, left);
            minY = Math.min(minY, top);
            maxX = Math.max(maxX, right);
            maxY = Math.max(maxY, bottom);
        });

        // Get SVG dimensions
        const svgRect = this.svgElement.getBoundingClientRect();
        const svgWidth = svgRect.width;
        const svgHeight = svgRect.height;

        // Calculate content dimensions with padding
        const padding = 50;
        const contentWidth = maxX - minX;
        const contentHeight = maxY - minY;

        // Calculate scale to fit (don't zoom in, only zoom out)
        const scale = Math.min(
            (svgWidth - padding * 2) / contentWidth,
            (svgHeight - padding * 2) / contentHeight,
            1  // Maximum scale of 1 (100%)
        );

        // Calculate the center of the content
        const contentCenterX = (minX + maxX) / 2;
        const contentCenterY = (minY + maxY) / 2;

        // Calculate translation to center the content in the SVG
        const translateX = svgWidth / 2 - contentCenterX * scale;
        const translateY = svgHeight / 2 - contentCenterY * scale;

        // Create transform and apply it
        const transform = this.d3.zoomIdentity
            .translate(translateX, translateY)
            .scale(scale);

        // Animate the zoom
        this.svg.transition()
            .duration(750)
            .call(this.zoom.transform, transform);
    }

}