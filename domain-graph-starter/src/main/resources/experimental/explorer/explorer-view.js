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