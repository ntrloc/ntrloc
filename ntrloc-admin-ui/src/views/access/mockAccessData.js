// TEMPORARY -- mock data for prototyping the Access screens (docs/ntrloc-drafts-plan.md, Phase 1).
// Only AccessDelegate reads it; nothing else may import it, and no test uses it. Delete it once
// AccessDelegate calls the real API.
//
// Shapes follow the real admin API where it exists (UserAdminController.UserView,
// UserGroupAdminController.UserGroupView, MarkerAdminController.MarkerView, TokenSummaryResponse).
// Fields the real API doesn't have yet are marked NOT IN API.

export function createMockAccessData() {
  return {
    users: [
      // signIn -- NOT IN API: how the user signs in, so the UI knows whether a password applies.
      { id: 'u-ana', displayName: 'Ana Torres', email: 'ana.torres@example.com', isAdmin: true, signIn: 'local' },
      { id: 'u-ben', displayName: 'Ben Okafor', email: 'ben.okafor@example.com', isAdmin: false, signIn: 'oidc' },
      { id: 'u-chen', displayName: 'Chen Wei', email: 'chen.wei@example.com', isAdmin: false, signIn: 'oidc' },
      { id: 'u-dana', displayName: 'Dana Reyes', email: 'dana.reyes@example.com', isAdmin: false, signIn: 'ldap' },
      { id: 'u-eli', displayName: 'Eli Novak', email: 'eli.novak@example.com', isAdmin: false, signIn: 'saml' },
      { id: 'u-fay', displayName: 'Fay Lindqvist', email: 'fay.lindqvist@example.com', isAdmin: true, signIn: 'oidc' },
    ],

    // parentIds as in UserGroupView. Members are kept separately (below), as the API keeps them.
    groups: [
      { id: 'g-everyone', name: 'everyone', parentIds: [] },
      { id: 'g-editors', name: 'Editors', parentIds: ['g-everyone'] },
      { id: 'g-senior', name: 'Senior editors', parentIds: ['g-editors'] },
      { id: 'g-contributors', name: 'Contributors', parentIds: ['g-everyone'] },
      { id: 'g-legal', name: 'Legal', parentIds: ['g-everyone'] },
    ],

    // Direct memberships: user -> groups. Everyone is in 'everyone'.
    memberships: {
      'u-ana': ['g-everyone'],
      'u-ben': ['g-everyone', 'g-editors'],
      'u-chen': ['g-everyone', 'g-senior'],
      'u-dana': ['g-everyone', 'g-contributors'],
      'u-eli': ['g-everyone', 'g-legal'],
      'u-fay': ['g-everyone'],
    },

    tokens: {
      'u-ana': [
        { id: 't-1', name: 'CI pipeline', createdAt: '2026-09-14T10:02:00Z', expiresAt: '2027-09-14T10:02:00Z' },
        { id: 't-2', name: 'Local scripts', createdAt: '2026-10-30T16:45:00Z', expiresAt: null },
      ],
      'u-chen': [{ id: 't-3', name: 'Import job', createdAt: '2026-11-02T08:15:00Z', expiresAt: '2026-12-02T08:15:00Z' }],
    },

    // A small schema to grant against: item types with their properties, link perspectives, state
    // machines; and markers scoped to item types (MarkerView).
    itemTypes: [
      {
        id: 'it-article',
        name: 'Article',
        properties: [
          { id: 'p-title', name: 'title' },
          { id: 'p-body', name: 'body' },
          { id: 'p-summary', name: 'summary' },
          { id: 'p-embargo', name: 'embargoUntil' },
        ],
        perspectives: [{ id: 'lp-article-authors', name: 'authors' }, { id: 'lp-article-images', name: 'images' }],
        stateMachines: [
          {
            id: 'sm-article',
            name: 'Publication',
            transitions: [
              { id: 'tr-submit', name: 'Submit' },
              { id: 'tr-approve', name: 'Approve' },
              { id: 'tr-publish', name: 'Publish' },
              { id: 'tr-retract', name: 'Retract' },
            ],
          },
        ],
      },
      {
        id: 'it-author',
        name: 'Author',
        properties: [
          { id: 'p-name', name: 'name' },
          { id: 'p-bio', name: 'bio' },
          { id: 'p-contact', name: 'contactEmail' },
        ],
        perspectives: [{ id: 'lp-author-articles', name: 'articles' }],
        stateMachines: [],
      },
      {
        id: 'it-image',
        name: 'Image',
        properties: [
          { id: 'p-caption', name: 'caption' },
          { id: 'p-credit', name: 'credit' },
          { id: 'p-file', name: 'file' },
        ],
        perspectives: [{ id: 'lp-image-articles', name: 'articles' }],
        stateMachines: [],
      },
    ],

    markers: [
      { id: 'm-public', name: 'Public', description: 'Published, visible to all readers', scopeKind: 'ITEM_TYPE', scopeId: 'it-article' },
      { id: 'm-internal', name: 'Internal', description: 'Work in progress', scopeKind: 'ITEM_TYPE', scopeId: 'it-article' },
      { id: 'm-embargoed', name: 'Embargoed', description: 'Not to be shown before its embargo date', scopeKind: 'ITEM_TYPE', scopeId: 'it-article' },
      { id: 'm-legal-hold', name: 'Legal hold', description: 'Under legal review', scopeKind: 'ITEM_TYPE', scopeId: 'it-article' },
      { id: 'm-author-public', name: 'Public profile', description: '', scopeKind: 'ITEM_TYPE', scopeId: 'it-author' },
      { id: 'm-author-private', name: 'Private details', description: '', scopeKind: 'ITEM_TYPE', scopeId: 'it-author' },
      { id: 'm-image-licensed', name: 'Licensed', description: '', scopeKind: 'ITEM_TYPE', scopeId: 'it-image' },
    ],

    // Grants, to a user or a group. The real API spreads these over several endpoints and tables;
    // here they're one list, shaped by what a grant says, to design the Permissions tab against.
    //   itemType: what a principal may do with an item type as a whole (item-type:read/create).
    //   marker:   what a principal may do with items carrying a marker -- read/delete the item, read/
    //             write each property, read/create/delete each link perspective, run transitions,
    //             start state machines.
    grants: [
      { principal: { kind: 'group', id: 'g-everyone' }, kind: 'itemType', itemTypeId: 'it-article', operations: ['read'] },
      { principal: { kind: 'group', id: 'g-everyone' }, kind: 'itemType', itemTypeId: 'it-author', operations: ['read'] },
      { principal: { kind: 'group', id: 'g-everyone' }, kind: 'itemType', itemTypeId: 'it-image', operations: ['read'] },
      { principal: { kind: 'group', id: 'g-editors' }, kind: 'itemType', itemTypeId: 'it-article', operations: ['read', 'create'] },
      { principal: { kind: 'group', id: 'g-contributors' }, kind: 'itemType', itemTypeId: 'it-image', operations: ['read', 'create'] },

      {
        principal: { kind: 'group', id: 'g-everyone' },
        kind: 'marker',
        markerId: 'm-public',
        item: { read: true, delete: false },
        properties: { 'p-title': { read: true, write: false }, 'p-body': { read: true, write: false }, 'p-summary': { read: true, write: false } },
        perspectives: { 'lp-article-authors': { read: true, create: false, delete: false } },
        transitions: [],
        stateMachineStarts: [],
      },
      {
        principal: { kind: 'group', id: 'g-editors' },
        kind: 'marker',
        markerId: 'm-internal',
        item: { read: true, delete: false },
        properties: {
          'p-title': { read: true, write: true },
          'p-body': { read: true, write: true },
          'p-summary': { read: true, write: true },
          'p-embargo': { read: true, write: false },
        },
        perspectives: {
          'lp-article-authors': { read: true, create: true, delete: true },
          'lp-article-images': { read: true, create: true, delete: true },
        },
        transitions: ['tr-submit'],
        stateMachineStarts: ['sm-article'],
      },
      {
        principal: { kind: 'group', id: 'g-senior' },
        kind: 'marker',
        markerId: 'm-internal',
        item: { read: true, delete: true },
        properties: { 'p-embargo': { read: true, write: true } },
        perspectives: {},
        transitions: ['tr-approve', 'tr-publish'],
        stateMachineStarts: [],
      },
      {
        principal: { kind: 'group', id: 'g-senior' },
        kind: 'marker',
        markerId: 'm-public',
        item: { read: true, delete: false },
        properties: {},
        perspectives: {},
        transitions: ['tr-retract'],
        stateMachineStarts: [],
      },
      {
        principal: { kind: 'group', id: 'g-legal' },
        kind: 'marker',
        markerId: 'm-legal-hold',
        item: { read: true, delete: false },
        properties: { 'p-title': { read: true, write: false }, 'p-body': { read: true, write: false } },
        perspectives: {},
        transitions: [],
        stateMachineStarts: [],
      },
      {
        principal: { kind: 'user', id: 'u-eli' },
        kind: 'marker',
        markerId: 'm-embargoed',
        item: { read: true, delete: false },
        properties: { 'p-embargo': { read: true, write: true } },
        perspectives: {},
        transitions: [],
        stateMachineStarts: [],
      },
      {
        principal: { kind: 'user', id: 'u-dana' },
        kind: 'itemType',
        itemTypeId: 'it-author',
        operations: ['read', 'create'],
      },
    ],
  }
}
