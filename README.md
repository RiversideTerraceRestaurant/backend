# Menu Maestro Backend

Node.js API with Supabase, Groq/Gemini and image processing.
Requires Node.js 22.12+.

## Local

Run `npm ci`, create your own `.env`, then `npm run dev`.
API: http://localhost:3001. Health: `/api/health`.

Required: `SUPABASE_URL`, `SUPABASE_ANON_KEY`.
AI: `GROQ_API_KEY` and/or `GEMINI_API_KEY`.
Optional: `PORT` (3001), `FRONTEND_ORIGIN` (http://localhost:8080),
`SESSION_SECRET`, `GROQ_MODEL`, `GEMINI_MODEL`, `AI_DAILY_LIMIT`, `KNOWLEDGE_BASE_PATH`.
Environment files are excluded from Git.

## EC2 / Production

Run `npm ci` and `npm start` under a process manager. Set `NODE_ENV=production`,
a stable `SESSION_SECRET` of at least 32 characters, and `FRONTEND_ORIGIN`
to the frontend HTTPS origin. Serve the API through Nginx with HTTPS.
Only set `TRUST_PROXY=true` when the trusted proxy overwrites X-Forwarded-For.

Set `KNOWLEDGE_BASE_PATH` to a persistent writable file and seed it from
`knowledge/restaurant.md`. Back up this file. Chat context, rate counters
and maintenance jobs currently live in RAM; use a single backend instance.

Migrations are in `supabase/migrations`; apply them to the intended project.
The deployment directory contains a combined frontend/backend Nginx example;
adapt it for an API-only host.

## Checks

`npm test`, `npm run lint`.
