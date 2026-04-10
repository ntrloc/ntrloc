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

        const photographer1 = new Node("Photographer", 'Bill Jones');
        const photographer2 = new Node("Photographer", 'Steve Jobs');
        const photo1 = new Node("Photo", "photo1.jpg");
        const photo2 = new Node("Photo", "photo2.jpg");
        const photo3 = new Node("Photo", "photo3.jpg");
        const photo4 = new Node("Photo", "photo4.jpg");

        const link1 = new Link(photographer1, photo1);
        const link2 = new Link(photographer1, photo2);
        const link3 = new Link(photographer1, photo3);
        const link4 = new Link(photographer1, photo4);

        const link5 = new Link(photographer2, photo1);

        this.nodes = [photographer1, photographer2, photo1, photo2, photo3, photo4];
        this.links = [link1, link2, link3, link4, link5];
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