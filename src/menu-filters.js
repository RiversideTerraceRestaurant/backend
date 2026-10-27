export const normalize = value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').toLowerCase();
export const priceWithVAT = item => Math.round(Number(item.price) * (1 + Number(item.vat || 0) / 100));
const meat = /\b(beef|pork|chicken|duck|lamb|bacon|ham|fish|shrimps?|prawns?|seafood|salmon|tuna|anchov(?:y|ies)|sausages?)\b/;
const dairy = /\b(milk|cream|butter|cheese|yogurt|parmesan|mozzarella)\b/;
const synonyms = {
  'hai san': ['seafood', 'shrimp', 'prawn', 'crab', 'fish', 'squid', 'mussel', 'oyster', 'clam', 'lobster'],
  seafood: ['seafood', 'shrimp', 'prawn', 'crab', 'fish', 'squid', 'mussel', 'oyster', 'clam', 'lobster'],
  shellfish: ['shrimp', 'prawn', 'crab', 'mussel', 'oyster', 'clam', 'lobster', 'scallop'],
  nuts: ['nut', 'peanut', 'almond', 'cashew', 'walnut', 'pistachio', 'hazelnut', 'pecan', 'pesto'],
  peanut: ['peanut', 'groundnut'],
  'dau phong': ['peanut', 'groundnut'], 'lac': ['peanut', 'groundnut'],
  sua: ['milk', 'cream', 'butter', 'cheese', 'yogurt', 'parmesan', 'mozzarella'],
  dairy: ['milk', 'cream', 'butter', 'cheese', 'yogurt', 'parmesan', 'mozzarella'],
  bo: ['beef'], 'thit bo': ['beef'], heo: ['pork', 'bacon', 'ham', 'sausage'], pork: ['pork', 'bacon', 'ham', 'sausage'],
  ga: ['chicken'], tom: ['shrimp', 'prawn'], egg: ['egg', 'mayonnaise', 'aioli'], gluten: ['wheat', 'bread', 'pasta', 'flour', 'soy sauce'],
};

// Badges are necessary evidence for dietary claims; descriptions may veto contradictory metadata.
export function filterItems(items, preferences) {
  const { diet = 'any', maxPrice = null, mild = false, avoid = [] } = preferences;
  return items.filter(item => {
    const tags = item.badges || [];
    const text = normalize(`${item.name} ${item.description} ${tags.join(' ')}`);
    if (maxPrice !== null && priceWithVAT(item) > maxPrice) return false;
    if (mild && (tags.includes('spicy') || /\b(spicy|chili|chilli|sriracha|jalapeno)\b/.test(text))) return false;
    if (diet === 'vegetarian' && (!(tags.includes('vegetarian') || tags.includes('vegan')) || meat.test(text))) return false;
    if (diet === 'vegan' && (!tags.includes('vegan') || meat.test(text) || dairy.test(text) || /\b(egg|honey)\b/.test(text))) return false;
    if (diet === 'gluten_free' && !tags.includes('gluten_free')) return false;
    // Existing menu uses the key "dairy" to mean dairy-free.
    if (diet === 'dairy_free' && (!tags.includes('dairy') || dairy.test(text))) return false;
    return avoid.every(term => {
      const normalized = normalize(term);
      return !(synonyms[normalized] || [normalized]).some(word => text.includes(word));
    });
  });
}

