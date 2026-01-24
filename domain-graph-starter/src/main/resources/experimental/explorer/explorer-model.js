export class Node {
    constructor(nodeType, name) {
        this.nodeType = nodeType;
        this.name = name;
    }
}

// link.js
export class Link {
    constructor(sourceNode, targetNode) {
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
    }
}

export class Graph {

    constructor() {
        this.listeners = [];

        const node1 = new Node("Photographer", 'Bill Jones');
        const node2 = new Node("Photo", "photo1.jpg");
        const node3 = new Node("Photo", "photo2.jpg");

        const link1 = new Link(node1, node2);
        const link2 = new Link(node1, node3);

        this.nodes = [node1, node2, node3];
        this.links = [link1, link2];
    }

    // Add a change listener
    addChangeListener(listener) {
        this.listeners.push(listener);
    }

    // Remove a change listener
    removeChangeListener(listener) {
        this.listeners = this.listeners.filter(l => l !== listener);
    }

    // Notify all listeners of changes
    notifyListeners() {
        this.listeners.forEach(listener => listener(this));
    }

    // Add a node and notify listeners
    addNode(node) {
        this.nodes.push(node);
        this.notifyListeners();
    }

    // Add a link and notify listeners
    addLink(link) {
        this.links.push(link);
        this.notifyListeners();
    }

    // Remove a node and notify listeners
    removeNode(node) {
        this.nodes = this.nodes.filter(n => n !== node);
        // Also remove any links connected to this node
        this.links = this.links.filter(l =>
            l.sourceNode !== node && l.targetNode !== node
        );
        this.notifyListeners();
    }

    // Remove a link and notify listeners
    removeLink(link) {
        this.links = this.links.filter(l => l !== link);
        this.notifyListeners();
    }

}