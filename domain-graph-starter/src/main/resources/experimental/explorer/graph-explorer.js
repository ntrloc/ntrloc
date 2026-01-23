export class GraphExplorer {

    constructor() {
        this.stylesId = 'graph-explorer-styles';
        this.d3Ready = this.loadD3();

        if (!document.getElementById(this.stylesId)) {
            this.injectStyles();
        }
    }

    async init() {
        const nodes = [
            { id: 'Node 1', x: 100, y: 100 },
            { id: 'Node 2', x: 300, y: 300 }
        ];

        const links = [
            { source: nodes[0], target: nodes[1] }
        ];

        const d3 = await this.d3Ready;

        this.$nextTick(() => {

            const thisElement = this.$el;
            thisElement.style.display = "flex";
            thisElement.style.flexDirection = "row";
            thisElement.style.fontFamily = "sans-serif";

            const sel = thisElement.querySelector("svg");
            const svg = d3.select(sel);

            // Create the force simulation
            const simulation = d3.forceSimulation(nodes)
                .force("link", d3.forceLink(links).id(d => d.id).distance(200))
                .force("charge", d3.forceManyBody().strength(-300))
                .force("center", d3.forceCenter(200, 200));

            const link = svg.selectAll("line")
                .data(links)
                .join("line")
                .attr("stroke", "red")
                .attr("stroke-width", 2);

            const node = svg.selectAll("rect")
                .data(nodes)
                .join("rect")
                .attr("x", d => d.x)
                .attr("y", d => d.y)
                .attr("width", 100)
                .attr("height", 100)
                .attr("stroke", "#95a6bf")
                .attr("stroke-width", 2)
                .attr("fill", "white")
                .attr("r", 50)
                .attr("cursor", "pointer")
                .call(drag(simulation));



            simulation.on("tick", () => {
                link
                    .attr("x1", d => d.source.x)
                    .attr("y1", d => d.source.y)
                    .attr("x2", d => d.target.x)
                    .attr("y2", d => d.target.y);
                node
                    .attr("x", d => d.x)
                    .attr("y", d => d.y);
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