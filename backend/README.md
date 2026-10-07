# XtremeX TV authorization backend

Deploy this directory as a separate Vercel project with Node 24 and Singapore functions. The existing TV playlist website uses a different project.

Set DATABASE_URL and a random SESSION_SECRET in Vercel environment variables. Apply sql/001_init.sql to the selected new Neon database before deployment. Never commit credentials or return them from API routes.

Run `npm ci`, `npm test` and `npm run typecheck`. Integration tests require a disposable Postgres database: `TEST_DATABASE_URL=... node --test test/integration/*.test.ts`. They create their schema and test accounts; never point them at the production branch.

App requests obtain a 60-second challenge, then send a base64 JSON payload and DER SHA256withECDSA signature. The signed string is action, normalized user ID, challenge ID, nonce and lowercase SHA256(payload bytes), joined with newlines. Public keys are base64 DER SPKI P-256. Challenges are single-use; sessions are hashed opaque tokens and are checked against current account status/generation.

The responsive admin panel lives in public/. Owner login uses a scrypt ADMIN_PASSWORD_HASH, HttpOnly Secure SameSite cookies, matching Origin and session-bound CSRF. Configure ADMIN_ORIGIN to the exact production origin. Each approve/block/unblock/reset checks the displayed device fingerprint and account generation; reload before acting on changed requests. Reset clears binding and requires a new device request and approval. Support number is editable in Settings.

2026-10-07 verification: 11 local tests and TypeScript check pass. Preview deployment dpl_D5u2d7TcDYuajmp76BwpDLTT9v6d reached READY after unit/typecheck and 6 real Neon integration tests on the separate test branch.

The owner explicitly authorized all four Production private variables. DATABASE_URL, SESSION_SECRET, ADMIN_PASSWORD_HASH and ADMIN_ORIGIN were saved and production deployment dpl_3x6BVrR7e7eKWtVUoD2uF99NSDk5 is READY at https://xtremex-tv-admin.vercel.app. Live HTTP checks passed: database health 200, unauthenticated dashboard 401, wrong-origin login 403, valid owner login 200 with Secure/HttpOnly/SameSite cookie, dashboard and settings 200, missing-CSRF logout 403, valid logout 200 and revoked cookie 401. No customer/test accounts were created in production.

Actual visual browser/mobile smoke remains unverified. Android build/signing is tracked separately; backend verification alone does not establish a complete APK release.

Challenges older than expiry plus five minutes are removed on new valid challenge creation. Shared-IP app limit is2400/min; admin-login5/min and signup10/min remain separate.
