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


// Beverage identity comes from its category/recipe, not a fruit-juice ingredient in a cocktail.
export function beverageProfile(item, category) {
  const group = normalize(category).replace(/[_-]/g, ' ');
  const text = normalize(`${item.name} ${item.description}`);
  const types = [];
  const mocktail = /\bmocktails?\b/.test(group);
  const cocktail = /\bcocktails?\b/.test(group);
  const softDrink = /\b(?:soft drinks?|juices?|smoothies?|beverages?)\b/.test(group);
  const coffee = /\bcoffee\b/.test(group);
  const tea = /\btea\b/.test(group);
  const beer = /\bbeers?\b/.test(group);
  const wine = /\b(?:wines?|prosecco)\b/.test(group);
  const spirit = /\bspirits?\b/.test(group);
  const liquor = /\b(?:vodka|gin|rum|tequila|whisk(?:e)?y|bourbon|brandy|cognac|liqueur|aperol|campari|cointreau|amaretto|baileys|vermouth|schnapps|sake|bacardi|kahlua)\b/.test(text);
  const alcoholFree = /\b(?:non[ -]?alcoholic|alcohol[ -]?free|zero[ -]?proof)\b|(?:^|\s)0[.,]0\s*%/.test(text);
  // Explicitly listed liquor vetoes an otherwise non-alcoholic category.
  const alcoholic = liquor || cocktail || spirit || (!alcoholFree && ((beer || wine) || /\b(?:wine|beer|prosecco|champagne|cider)\b/.test(text)));
  const nonAlcoholic = !alcoholic && (mocktail || softDrink || coffee || tea || alcoholFree);
  if (mocktail) types.push('mocktail');
  if (cocktail) types.push('cocktail');
  if (softDrink) types.push('soft_drink');
  if (coffee) types.push('coffee');
  if (tea || (softDrink && /\btea\b/.test(text))) types.push('tea');
  if (spirit) types.push('spirit');
  // Some menus combine beer and wine in one category: distinguish them by the recipe/name.
  if (beer && (!wine || /\b(?:beer|lager|ale|pilsner|stout|ipa|heineken|tiger|saigon|huda)\b/.test(text))) types.push('beer');
  if (wine && (!beer || /\b(?:wine|prosecco|merlot|cabernet|chardonnay|sauvignon|shiraz|pinot|montepulciano)\b/.test(text))) types.push('wine');
  if (nonAlcoholic && !mocktail && !coffee && !tea && softDrink
    && /\b(?:juice|smoothie)\b/.test(text)
    && !/\b(?:tea|coffee|milk|cream|yogurt|soda|tonic|ginger ale)\b/.test(text)) types.push('juice');
  return { types, alcohol: alcoholic ? 'alcoholic' : nonAlcoholic ? 'none' : 'unknown' };
}

const alcoholFreePhrases = /\b(?:khong\s+(?:co\s+)?con|non[ -]?alcoholic|alcohol[ -]?free|no alcohol|without alcohol|sans alcool|sin alcohol|senza alcol)\b|без\s+алкоголя|безалкоголь|алкогольсіз|спиртсіз|무알(?:코올|콜)|无酒精|無酒精|ノンアルコール|アルコールなし/;
export const requestsAlcoholFree = text => alcoholFreePhrases.test(normalize(text).normalize('NFC'));
export const isAlcoholOnlyFollowup = text => [
  'khong con', 'khong co con', 'do uong khong con', 'non-alcoholic', 'non alcoholic',
  'alcohol-free', 'alcohol free', 'no alcohol', 'without alcohol', 'sans alcool', 'sin alcohol',
  'senza alcol', 'без алкоголя', 'безалкогольное', 'алкогольсіз', 'спиртсіз',
  '무알코올', '무알콜', '无酒精', '無酒精', 'ノンアルコール', 'アルコールなし',
].includes(normalize(text).normalize('NFC').replace(/[?？!！.,。]/g, '').trim());

// Ground flavour prose in known ingredient characteristics, without inventing recipe details.
export function menuFlavourNotes(item) {
  const text = normalize(item.description);
  return [
    [/\bsmoked salmon\b/, 'Smoked salmon: rich, smoky flavour.'],
    [/\bsalmon\b/, 'Salmon: richness; no doneness or texture is confirmed.'],
    [/\b(?:cream|cream cheese|mozzarella)\b/, 'Cream or cheese: creamy richness; do not call it sweet or crisp.'],
    [/\bparmesan\b/, 'Parmesan: savoury, salty depth; no crispness is stated.'],
    [/\btomato(?:es)?\b/, 'Tomato: mild acidity balancing rich ingredients.'],
    [/\bcapers?\b/, 'Capers: salty, tangy contrast.'],
    [/\bgarlic\b/, 'Garlic: aromatic, savoury character.'],
    [/\borange\b/, 'Orange: citrus aroma and sweet-tart character.'],
    [/\bbutter\b/, 'Butter: richness.'],
    [/\bsweet potatoes\b/, 'Sweet potatoes: mild sweetness.'],
    [/\bpesto\b/, 'Pesto: herbal aroma; do not assume extra ingredients.'],
    [/\b(?:rocket|rockets|arugula|mint|dill)|thi la/, 'Listed herbs: herbal aroma; do not infer chili heat.'],
    [/\blychee(?:s)?\b/, 'Lychee: sweetness and floral aroma.'],
    [/\bpineapple\b/, 'Pineapple: sweet-tart tropical flavour.'],
    [/\bmelon\b/, 'Melon: mild sweetness; its variety is unspecified, so do not say watermelon.'],
    [/\b(?:crispy|crunchy|toasted bread)\b/, 'The stated crispy/crunchy/toasted-bread component may add a crisp contrast.'],
  ].filter(([pattern]) => pattern.test(text)).map(([, note]) => note);
}

// Deterministic tasting notes also work during provider rate limits; no extra AI call.
export function menuFlavourSummary(item, language = 'en') {
  const text = normalize(item.description);
  const profiles = [
    ['rich', /\b(?:salmon|cream|cheese|butter|parmesan|mozzarella)\b/],
    ['tart', /\b(?:tomato(?:es)?|orange|pineapple|lime|lemon|capers?)\b/],
    ['salty', /\b(?:parmesan|capers?|olives?)\b/],
    ['aromatic', /\b(?:garlic|pesto|rocket|rockets|arugula|mint|dill)\b|thi la/],
    ['sweet', /\b(?:lychee(?:s)?|melon|orange|pineapple|honey|sweet potatoes)\b/],
    ['smoky', /\bsmoked\b/],
    ['spicy', /\b(?:spicy|chili|chilli|jalapeno|sriracha)\b/],
  ].filter(([, pattern]) => pattern.test(text)).map(([key]) => key);
  if (!profiles.length) return '';
  const wording = {
    en: ['It combines {notes}.', 'richness', 'gentle acidity', 'salty depth', 'aromatic notes', 'sweetness', 'smoky notes', 'chili heat'],
    vi: ['Hương vị kết hợp {notes}.', 'vị béo', 'chút chua thanh', 'vị mặn đậm đà', 'hương thơm', 'vị ngọt', 'hương khói', 'vị cay'],
    ko: ['{notes}이 어우러집니다.', '풍부한 맛', '은은한 산미', '짭짤한 풍미', '향긋한 풍미', '단맛', '훈연 향', '매운맛'],
    ja: ['{notes}が調和します。', 'コク', '穏やかな酸味', '塩味のうまみ', '香り', '甘み', '燻製の香り', '辛み'],
    cn: ['风味融合了{notes}。', '醇厚感', '清爽酸味', '咸鲜味', '清香', '甜味', '烟熏香气', '辣味'],
    ru: ['Во вкусе сочетаются {notes}.', 'насыщенность', 'лёгкая кислинка', 'солоноватые ноты', 'ароматические ноты', 'сладость', 'дымные ноты', 'острота'],
    kz: ['Дәмінде {notes} үйлеседі.', 'қанық дәм', 'жеңіл қышқылдық', 'тұзды реңк', 'хош иіс', 'тәттілік', 'ысталған хош иіс', 'ащылық'],
    es: ['Combina {notes}.', 'untuosidad', 'acidez suave', 'notas saladas', 'notas aromáticas', 'dulzor', 'notas ahumadas', 'picante'],
    fr: ['Les saveurs associent {notes}.', 'rondeur', 'acidité douce', 'notes salées', 'notes aromatiques', 'douceur', 'notes fumées', 'piquant'],
    it: ['Il gusto unisce {notes}.', 'ricchezza', 'delicata acidità', 'note sapide', 'note aromatiche', 'dolcezza', 'note affumicate', 'piccantezza'],
  };
  const words = wording[language] || wording.en;
  const labels = Object.fromEntries(['rich', 'tart', 'salty', 'aromatic', 'sweet', 'smoky', 'spicy'].map((key, index) => [key, words[index + 1]]));
  const locale = language === 'cn' ? 'zh' : language === 'kz' ? 'kk' : language;
  return words[0].replace('{notes}', new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }).format(profiles.map(key => labels[key])));
}
