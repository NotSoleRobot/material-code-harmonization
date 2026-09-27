# Hosted deployment: Render + Supabase

This deployment is independent of the development computer. Render runs the frontend,
Spring backend, and Python matching service; Supabase runs PostgreSQL. The existing
Docker Compose and localhost configuration remain available and unchanged.

## Hosted architecture

```text
Browser
  -> Render static site (React)
       -> Render web service (Spring Boot API)
            -> Supabase PostgreSQL
            -> Render web service (Python matching)
```

The Python service is protected with a generated service token. Its `/health` route
remains public for Render health checks; matching routes only accept calls from the
Spring backend when the hosted configuration is active.

## 1. Put the repository on GitHub or GitLab

Render deploys this setup from a Git repository. Push the repository without committing
real passwords, JWT secrets, database credentials, or service tokens.

## 2. Create the hosted PostgreSQL database

Create a free Supabase project and copy its database connection details from the
Supabase dashboard. Prefer the session-pooler connection details when the direct
database host is not reachable over IPv4.

Enter these values when Render asks for Blueprint environment variables:

| Render variable | Value |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://HOST:PORT/postgres?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | Supabase database/pooler user |
| `SPRING_DATASOURCE_PASSWORD` | Supabase database password |

Use a new hosted database. Flyway creates the application schema and seed reference
data on the first backend start.

## 3. Create the Render Blueprint

In the Render dashboard, choose **New > Blueprint**, connect the repository, and use
the root-level `render.yaml`. It creates:

- `numm-frontend`: React static site
- `numm-backend`: Spring Boot API
- `numm-matching`: Python matching service

Render prompts for every variable marked `sync: false`. In addition to the database
values, provide:

| Variable | Required value |
|---|---|
| Frontend `VITE_API_BASE_URL` | Public backend origin, for example `https://numm-backend.onrender.com` |
| Backend `MATCHING_SERVICE_URL` | Public matching-service origin, for example `https://numm-matching.onrender.com` |
| Backend `CORS_ALLOWED_ORIGINS` | Exact frontend origin, for example `https://numm-frontend.onrender.com` |
| Backend `BOOTSTRAP_ADMIN_PASSWORD` | A new, private password with at least 14 characters |

Do not add `/api` to `VITE_API_BASE_URL`; the frontend adds it. Do not add a trailing
slash to any of the three service origins.

Render service names must be unique. If Render changes a name or adds a suffix, finish
creating the services, copy their actual `onrender.com` origins, correct the three URL
variables above, and manually redeploy the affected services. The frontend variable is
compiled into its build, so changing it requires a new frontend deploy.

## 4. First-start behavior

The backend runs the profiles `python-matching,hosted`.

- Flyway creates or upgrades the database.
- The known seeded admin account receives `BOOTSTRAP_ADMIN_PASSWORD`.
- Seeded reviewer and operator demo accounts are disabled.
- The administrator can sign in as `admin@numm.gov.in` and create real users from User
  Management.

The hosted initializer deliberately fails startup when the bootstrap password is
missing or shorter than 14 characters. This prevents publishing the known local demo
password.

## 5. Verify the deployment

Check these endpoints before using the UI:

1. `https://YOUR-MATCHING-SERVICE.onrender.com/health` returns HTTP 200.
2. `https://YOUR-BACKEND.onrender.com/actuator/health` reports `UP`.
3. Open the frontend URL and sign in with the hosted admin password.
4. Create a non-admin test user, sign in as that user, ingest a sample file, and confirm
   the resulting material appears in My Materials and the appropriate review/catalog
   flow.

Free Render web services can spin down while idle, so the first request after inactivity
may take noticeably longer. Supabase may also pause an inactive free project. These are
free-tier constraints, not a dependency on the local computer.

## Local development remains available

Nothing in this hosted setup removes the local configuration:

```powershell
docker compose up -d --build
```

The local frontend continues to use `/api` through its existing proxy, the backend
continues to default to port `8080`, and the matching service continues to default to
port `5000`. The hosted-only account rules run only under the `hosted` Spring profile.
