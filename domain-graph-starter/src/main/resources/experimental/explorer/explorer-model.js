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
        const node1 = new Node("Photographer", 'Bill Jones');
        const node2 = new Node("Photo", "photo1.jpg");
        const node3 = new Node("Photo", "photo2.jpg");

        const link1 = new Link(node1, node2);
        const link2 = new Link(node1, node3);

        this.nodes = [node1, node2, node3];
        this.links = [link1, link2];
    }

}