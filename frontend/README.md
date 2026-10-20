# Central Archive Frontend

Next.js frontend for the CENTRAL-ARCHIVE-SYSTEM backend.

## Unified source

This directory combines the frontend already integrated in `main` at `202d33b`
with the application screens from `frontEnd` at `144b47f`.
Work on this frontend from this directory together with the root `backend/`.

The integration retains the main branch's access/refresh HttpOnly cookies,
refresh-token rotation, streaming multipart gateway, backend error codes, and
encrypted package upload screen. It adds the standalone frontend's operation,
query, record detail, dropdown, review queue and incomplete-report screens.
`ApiRequestError` and `ApiClientError` refer to the same error class so both sets
of screens keep their error handling and automatic session refresh.

The migration settings screen only edits `migrationEnabled`, matching the
current backend DTO. The old standalone confidentiality-code setting is not
part of that API contract. Postman collections remain in the repository root.

## Local development

```powershell
Copy-Item .env.example .env.local
npm ci
npm run dev
```

The default backend URL is `http://localhost:8080` through the server-only `CENTRAL_ARCHIVE_API_URL` variable.

The browser talks only to the internal Next.js BFF. Access and refresh tokens are stored in HttpOnly cookies and the backend URL is not exposed to client code.

The dashboard layout waits for a real request before checking authentication.
Production images can therefore be built without `CENTRAL_ARCHIVE_API_URL` and
receive it at runtime through Compose; a missing build-time URL must never
prerender a permanent redirect to login. Only an actual 401 redirects to login.

## Quality checks

```powershell
npm run lint
npm test
npm run build
```

The root repository Postman collections remain the API contract reference.
