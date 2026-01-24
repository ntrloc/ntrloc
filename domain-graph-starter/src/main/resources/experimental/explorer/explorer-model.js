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