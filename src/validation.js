import { z } from 'zod';

export const idSchema = z.string().uuid();
export const badges = ['pork', 'beef', 'vegetarian', 'vegan', 'gluten_free', 'spicy', 'dairy'];
export const translationKeys = ['description_ko', 'description_ja', 'description_cn', 'description_vi', 'description_ru', 'description_kz', 'description_es', 'description_fr', 'description_it'];
export const itemSchema = z.object({
  name: z.string().trim().min(1).max(200), description: z.string().max(5000),
  price: z.number().finite().min(0).max(100000000), vat: z.number().finite().min(0).max(100),
  category_id: idSchema.nullable(), badges: z.array(z.enum(badges)).max(7),
  image_url: z.string().url().max(2000).nullable(), thumbnail_url: z.string().url().max(2000).nullable(),
  ...Object.fromEntries(translationKeys.map(key => [key, z.string().max(5000).nullable().optional()])),
}).strict();
export const categorySchema = z.object({
  name: z.string().trim().min(1).max(150), display_name: z.string().trim().min(1).max(150),
  display_order: z.number().int().min(0).max(10000), is_active: z.boolean(),
}).strict();
export const preferencesSchema = z.object({
  diet: z.enum(['any', 'vegetarian', 'vegan', 'gluten_free', 'dairy_free']).default('any'),
  maxPrice: z.number().finite().min(0).max(100000000).nullable().default(null),
  mild: z.boolean().default(false), avoid: z.array(z.string().trim().min(1).max(80)).max(10).default([]),
});
export const languageSchema = z.enum(['en', 'vi', 'ko', 'ja', 'cn', 'ru', 'kz', 'es', 'fr', 'it']);
export const chatSchema = z.object({
  conversationId: z.string().uuid().optional(),
  context: z.enum(['home', 'menu']).default('menu'),
  action: z.enum(['chat', 'suggest', 'popular', 'vegetarian', 'mild', 'availability']).default('chat'),
  language: languageSchema.default('en'),
  messages: z.array(z.object({ role: z.enum(['user', 'assistant']), content: z.string().trim().min(1).max(1000) })).min(1).max(12),
  preferences: preferencesSchema.default({}),
  // Structured table-availability request from the chat's quick form.
  booking: z.object({
    date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
    time: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/).optional(),
    guests: z.number().int().min(1).max(50).optional(),
    seating: z.enum(['inside', 'outside']).optional(),
  }).optional(),
}).refine(value => value.messages.at(-1)?.role === 'user', 'The last message must be from the guest.');
