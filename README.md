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

## GitHub Actions

The CI workflow runs on every push, on pull requests targeting `main`, and
manually from the repository's Actions tab. It uses Node.js 24 and installs
locked dependencies with `npm ci`. No production secrets are required.

Checks: JavaScript syntax and the Node.js test suite. Tests use mocks and local
fixtures; they do not require production Supabase or AI credentials. After checks pass on `main`, the workflow deploys the API to EC2.
Database migrations are not run automatically.

## Automatic EC2 deployment

Pushes to `main` deploy after CI succeeds. Pull requests only run checks.
Manual runs on `main` also deploy. Runs on `main` are serialized so a newer
push does not interrupt an active deployment.

Repository variables: `EC2_HOST`, `EC2_USER`.
Repository secrets: `EC2_SSH_KEY` (dedicated deployment key),
`EC2_KNOWN_HOSTS` (verified server host keys). Production application secrets
stay on EC2; do not add them to the frontend build.

`deployment/deploy.sh` fetches and checks out the exact checked commit in
`/home/ubuntu/apps/backend`, installs production dependencies under Node.js 24,
restarts PM2 process `rtr-backend`, and checks `/api/health`. A failed deploy
attempts to restore the previous commit, dependencies and process. Local
changes to tracked server files stop deployment. `.env` and persistent files
under `/home/ubuntu/app-data` are preserved. Code runs from a detached commit;
use `git log -1` to inspect the deployed revision.
