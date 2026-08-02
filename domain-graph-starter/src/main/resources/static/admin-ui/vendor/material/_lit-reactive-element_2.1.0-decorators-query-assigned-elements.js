/**
 * Bundled by jsDelivr using Rollup v4.62.2 and esbuild v0.28.1.
 * Original file: /npm/@lit/reactive-element@2.1.0/decorators/query-assigned-elements.js
 *
 * Do NOT use SRI with dynamically generated files! More information: https://www.jsdelivr.com/using-sri-with-dynamic-files
 */
const a=(t,r,e)=>(e.configurable=!0,e.enumerable=!0,Reflect.decorate&&typeof r!="object"&&Object.defineProperty(t,r,e),e);function i(t){return(r,e)=>{const{slot:n,selector:o}=t??{},c="slot"+(n?`[name=${n}]`:":not([name])");return a(r,e,{get(){const l=this.renderRoot?.querySelector(c),s=l?.assignedElements(t)??[];return o===void 0?s:s.filter((u=>u.matches(o)))}})}}export{i as queryAssignedElements};
//# sourceMappingURL=/sm/d1d84fec6f87a60cf2b52b719a709e27f8098f1aa6838621244e60f7d8b77dce.map