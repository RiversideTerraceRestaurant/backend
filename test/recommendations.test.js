import test from 'node:test';
import assert from 'node:assert/strict';
import { filterItems, priceWithVAT, recommend } from '../src/recommendations.js';
import { chatSchema } from '../src/validation.js';

const items = [
  { id: '1', name: 'Garden salad', description: 'Tomato, lettuce and olive oil', price: 90000, vat: 10, badges: ['vegan', 'vegetarian'] },
  { id: '2', name: 'Beef pasta', description: 'Beef, tomato and cheese', price: 190000, vat: 10, badges: ['beef'] },
  { id: '3', name: 'Spicy tofu', description: 'Tofu with chili sauce', price: 100000, vat: 8, badges: ['vegan', 'spicy'] },
  { id: '4', name: 'Peanut noodles', description: 'Peanut and soy sauce', price: 80000, vat: 0, badges: ['vegan'] },
  { id: '5', name: 'Prawn salad', description: 'Prawns, lettuce', price: 110000, vat: 0, badges: [] },
  { id: '6', name: 'Mislabeled soup', description: 'Chicken, butter and cream', price: 75000, vat: 0, badges: ['vegan', 'dairy'] },
];
const input = (extra = {}) => chatSchema.parse({ language: 'vi', action: 'suggest', messages: [{ role: 'user', content: 'Gợi ý món nhé' }], ...extra });
const offline = { configured: false, json: async () => { throw new Error('provider unavailable'); } };

test('budget includes VAT, including the boundary amount', () => {
  assert.equal(priceWithVAT(items[1]), 209000);
  assert.equal(filterItems([items[1]], { maxPrice: 200000 }).length, 0);
  assert.equal(filterItems([items[1]], { maxPrice: 209000 }).length, 1);
});
test('diet requires explicit menu evidence and rejects contradictory metadata', () => {
  const vegan = filterItems(items, { diet: 'vegan' }).map(item => item.id);
  assert.deepEqual(vegan, ['1', '3', '4']);
  assert.deepEqual(filterItems(items, { diet: 'gluten_free' }), []);
  assert.deepEqual(filterItems(items, { diet: 'dairy_free' }), []);
});
test('combines vegan, budget, spice and allergen-family exclusions', () => {
  assert.deepEqual(filterItems(items, { diet: 'vegan', maxPrice: 100000, mild: true, avoid: ['nuts'] }).map(item => item.id), ['1']);
  assert.equal(filterItems([items[4]], { avoid: ['hải sản'] }).length, 0);
});
test('provider failure does not return guesses for a free-text request', async () => {
  const result = await recommend({ input: input({ action: 'chat', messages: [{ role: 'user', content: 'Tôi dị ứng đậu phộng' }] }), items, ai: offline });
  assert.equal(result.mode, 'unavailable');
  assert.deepEqual(result.items, []);
});
test('structured suggestions keep working without an AI key', async () => {
  const result = await recommend({ input: input({ preferences: { diet: 'vegan', mild: true, avoid: ['nuts'] } }), items, ai: offline });
  assert.equal(result.mode, 'filters');
  assert.deepEqual(result.items.map(item => item.id), ['1']);
  assert.equal(result.items[0].price_with_vat, 99000);
});
test('AI cannot relax explicit filters or supply invented item IDs', async () => {
  let calls = 0;
  const ai = { json: async () => ++calls === 1 ? { diet: 'any', maxPrice: null, mild: false, avoid: [], keywords: [], popular: false, clarification: '' } : { reply: 'Mời bạn thử món này.', itemIds: ['invented'] } };
  const result = await recommend({ input: input({ action: 'chat', preferences: { diet: 'vegan', mild: true, maxPrice: 100000, avoid: ['nuts'] } }), items, ai });
  assert.deepEqual(result.items.map(item => item.id), ['1']);
});
test('natural-language dietary requirements intersect with explicit filters', async () => {
  const ai = { json: async () => ({ diet: 'vegan', maxPrice: null, mild: false, avoid: [], keywords: [], popular: false, clarification: '' }) };
  const result = await recommend({ input: input({ action: 'chat', preferences: { diet: 'gluten_free' } }), items, ai });
  assert.deepEqual(result.items, []);
});
test('asks for clarification instead of suggesting against an ambiguous group budget', async () => {
  const ai = { json: async () => ({ diet: 'any', maxPrice: null, mild: false, avoid: [], keywords: [], popular: false, clarification: 'Ngân sách này là cho mỗi món hay cả bàn?' }) };
  const result = await recommend({ input: input({ action: 'chat' }), items, ai });
  assert.deepEqual(result.items, []);
  assert.match(result.reply, /cả bàn/);
});
test('popularity uses actual view counts and discloses absent tracking data', async () => {
  const result = await recommend({ input: input({ action: 'popular' }), items, counts: [{ menu_item_id: '2', view_count: '99' }], popularityAvailable: true, ai: offline });
  assert.equal(result.items[0].id, '2');
  const emptyCounts = await recommend({ input: input({ action: 'popular' }), items, ai: offline });
  assert.match(emptyCounts.reply, /Chưa đủ dữ liệu/);
});

test('default recommendations prioritize food and diversify categories instead of picking cheap drinks', async () => {
  const catalog = [
    { ...items[0], category_id: 'starters' },
    { ...items[1], category_id: 'mains' },
    { ...items[2], category_id: 'starters' },
    { ...items[3], category_id: 'pasta' },
    { id: 'water', name: 'Water', description: '', price: 10000, vat: 0, category_id: 'drinks' },
  ];
  const categories = ['starters', 'mains', 'pasta', 'drinks'].map(id => ({ id, name: id, display_name: id }));
  const result = await recommend({ input: input(), items: catalog, categories, ai: offline });
  assert.ok(!result.items.some(item => item.id === 'water'));
  assert.equal(new Set(result.items.slice(0, 3).map(item => item.category_id)).size, 3);
});

test('one RTR AI answers restaurant questions from the menu page without needing menu storage', async () => {
  let restaurantCalls = 0;
  const result = await recommend({
    input: { ...input({ action: 'chat' }), context: 'menu' },
    ai: { json: async () => ({ topic: 'restaurant' }) },
    getCatalog: async () => { throw new Error('Menu database must not be needed for a restaurant question'); },
    restaurant: async () => { restaurantCalls++; return { reply: 'Verona có guitar.', items: [], mode: 'ai', sources: [{ id: 'section-3', title: 'Không gian' }] }; },
  });
  assert.equal(restaurantCalls, 1);
  assert.match(result.reply, /guitar/);
  assert.equal(result.sources.length, 1);
});

test('one RTR AI can recommend dishes on home and answer a mixed question with real dishes and knowledge sources', async () => {
  for (const topic of ['menu', 'both']) {
    let restaurantCalls = 0;
    const result = await recommend({
      input: { ...input({ action: 'chat' }), context: 'home' }, items,
      ai: { json: async (system, data) => data.dishes ? { reply: 'Các món phù hợp.' } : { topic, diet: 'any', maxPrice: null, mild: false, avoid: [], keywords: [], clarification: '' } },
      restaurant: async () => { restaurantCalls++; return { reply: 'Nhà hàng ở Đà Nẵng.', sources: [{ id: 'address', title: 'Địa chỉ' }], mode: 'ai', items: [] }; },
    });
    assert.ok(result.items.length > 0);
    assert.ok(result.items.every(item => items.some(real => real.id === item.id)));
    assert.equal(restaurantCalls, topic === 'both' ? 1 : 0);
    if (topic === 'both') { assert.match(result.reply, /Đà Nẵng/); assert.equal(result.sources[0].title, 'Địa chỉ'); }
  }
});

test('a corrected topic replaces compact memory without reusing its subjects as menu filters', async () => {
  let state;
  const previous = { memory: { summary: 'Guest asked about beef and Verona.', keywords: ['beef', 'Verona'] } };
  const updated = { summary: 'Guest now wants tofu, not beef.', keywords: ['tofu', 'tofu'] };
  const ai = { json: async (system, data) => {
    if (data.dishes) {
      assert.deepEqual(data.conversationContext.keywords, ['tofu']);
      return { reply: 'Try the tofu.' };
    }
    assert.deepEqual(data.conversationContext, previous.memory);
    assert.match(system, /latest explicit guest request overrides old context/);
    return { topic: 'menu', keywords: ['tofu'], memory: updated };
  } };
  const result = await recommend({ input: input({ action: 'chat' }), previous, items, ai, onState: value => { state = value; } });
  assert.deepEqual(result.items.map(item => item.id), ['3']);
  assert.deepEqual(state.memory.keywords, ['tofu']);
  assert.equal(result.preferences.memory, undefined);
});

test('explicitly cleared memory replaces old context in both state and restaurant replies', async () => {
  let state;
  const memory = { summary: '', keywords: [] };
  const result = await recommend({
    input: input({ action: 'chat' }),
    previous: { selectedIds: ['1'], memory: { summary: 'Earlier request', keywords: ['Verona'] } },
    ai: { json: async () => ({ topic: 'restaurant', memory }) },
    onState: value => { state = value; },
    restaurant: async context => {
      assert.deepEqual(context.memory, memory);
      return { reply: 'What would you like to know?', items: [], mode: 'ai' };
    },
  });
  assert.equal(result.mode, 'ai');
  assert.deepEqual(state.memory, memory);
  assert.equal(state.selectedIds, undefined);
});

test('malformed model memory does not discard a valid intent or replace bounded prior memory', async () => {
  let state;
  const previous = { memory: { summary: 'Guest is choosing a dish.', keywords: ['food'] } };
  const ai = { json: async (_system, data) => data.dishes ? { reply: 'Try this dish.' } : { topic: 'menu', memory: { summary: 'x'.repeat(701), keywords: Array(20).fill('invalid') } } };
  const result = await recommend({ input: input({ action: 'chat' }), previous, items, ai, onState: value => { state = value; } });
  assert.equal(result.mode, 'ai');
  assert.ok(result.items.length > 0);
  assert.deepEqual(state.memory, previous.memory);
});
