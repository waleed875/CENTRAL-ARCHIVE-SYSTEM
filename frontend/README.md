# Central Archive Frontend

Next.js frontend for the CENTRAL-ARCHIVE-SYSTEM backend.

## Local development

```powershell
Copy-Item .env.example .env.local
npm ci
npm run dev
```

The default backend URL is `http://localhost:8080` through the server-only `CENTRAL_ARCHIVE_API_URL` variable.

The browser talks only to the internal Next.js BFF. Access and refresh tokens are stored in HttpOnly cookies and the backend URL is not exposed to client code.

## Quality checks

```powershell
npm run lint
npm test
npm run build
```

The root repository Postman collections remain the API contract reference.
