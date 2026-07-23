/**
 * Bundled by jsDelivr using Rollup v4.62.2 and esbuild v0.28.1.
 * Original file: /npm/@lit/reactive-element@2.1.0/decorators/query-assigned-nodes.js
 *
 * Do NOT use SRI with dynamically generated files! More information: https://www.jsdelivr.com/using-sri-with-dynamic-files
 */
const s=(t,r,e)=>(e.configurable=!0,e.enumerable=!0,Reflect.decorate&&typeof r!="object"&&Object.defineProperty(t,r,e),e);function u(t){return(r,e)=>{const{slot:o}=t??{},n="slot"+(o?`[name=${o}]`:":not([name])");return s(r,e,{get(){return this.renderRoot?.querySelector(n)?.assignedNodes(t)??[]}})}}export{u as queryAssignedNodes};
//# sourceMappingURL=/sm/0fae8a22726627741580e4adf950b05065ae623a845b672d181876643909962e.map