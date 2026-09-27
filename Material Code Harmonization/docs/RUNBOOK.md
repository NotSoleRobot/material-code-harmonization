# NUMM — Operational Runbook

## Architecture overview

```
┌────────────┐     ┌──────────────────┐     ┌──────────────────┐     ┌──────────────┐
│  Frontend   │────▶│  Spring Boot      │────▶│  Flask Matching  │     │  PostgreSQL  │
│  React SPA  │     │  Backend (8080)   │     │  Service (5000)  │     │  16 (5433)   │
│  nginx:3000 │     │                   │────▶│                  │     │              │
└────────────┘     └──────────────────┘     └──────────────────┘     └──────────────┘
      │                    │                                               │
      └── vite dev: 5173   └───────────────────────────────────────────────┘
          (proxies /api)
```

## Ports

| Service | Container port | Host port | Notes |
|---------|---------------|-----------|-------|
| PostgreSQL | 5432 | **5433** | Mapped to 5433 to avoid collision with a native Postgres install |
| Flask matching service | 5000 | 5000 | ML model inference |
| Spring Boot backend | 8080 | 8080 | REST API |
| Frontend (nginx) | 80 | 3000 | Production build served by nginx |
| Frontend (vite dev) | 5173 | 5173 | Development mode with HMR |

## Spring profiles

| Profile | When to use | What it does |
|---------|-------------|--------------|
| `python-matching` | Docker Compose (default) | Uses the Flask service for ML matching |
| `demo` | Optional; enabled by Docker Compose | Exposes real seeded accounts through `GET /api/auth/demo-accounts`; it does not replace authentication or matching |

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/material_master` | JDBC connection string |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | DB password |
| `MATCHING_SERVICE_URL` | `http://localhost:5000` | Flask service base URL |
| `JWT_SECRET` | *(none)* | 256-bit key for JWT signing. It is required outside the checked-in `demo` profile. |
| `SPRING_PROFILES_ACTIVE` | `python-matching` | Comma-separated active profiles |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Comma-separated allowed CORS origins |
| `PORT` (Flask) | `5000` | Matching service port |

## Development mode

### Backend + matching service (Docker)
```bash
docker compose up postgres matching-service
```
Then run the Spring Boot app from your IDE with:
```
-Dspring.profiles.active=python-matching,demo
-DSPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/material_master
```

### Frontend (vite dev server)
```bash
cd frontend
npm install
npm run dev
```
Vite proxies `/api` → `http://localhost:8080` (configured in `vite.config.js`).

### Full Docker stack
```bash
docker compose up --build
```
Frontend at `http://localhost:3000`, backend at `http://localhost:8080`.

## Proxy configuration

| Mode | Frontend | → Backend | Config file |
|------|----------|-----------|-------------|
| Development | `localhost:5173` | `localhost:8080` | `frontend/vite.config.js` (`proxy: { '/api': ... }`) |
| Production | `localhost:3000` (nginx) | `backend:8080` (Docker DNS) | `frontend/nginx.conf` (`proxy_pass http://backend:8080`) |

Both paths use same-origin `/api` calls in `services/api.js`.

## Database migrations

Flyway runs automatically on startup. Migrations live in:
```
material-master-backend/src/main/resources/db/migration/
```

**Never edit an applied migration.** All schema changes go in new `V<N>__<description>.sql` files.

## ML model retraining

```bash
cd matching-service
PYTHONPATH=src python src/matching/train.py
```

The trained artifacts are saved to `matching-service/models/`. The committed models
were serialized with scikit-learn 1.8.0 — ensure the pinned version matches before retraining.

## Packaging & Artifact Distribution

### 1. Build Production Executable Backend JAR
```bash
cd material-master-backend
mvn clean package -DskipTests
```
Output: `material-master-backend/target/material-master-backend-0.1.0.jar`

### 2. Build Frontend Production Bundle
```bash
cd frontend
npm run build
```
Output: `frontend/dist/`

### 3. Generate Clean Source Zip Archive
```bash
python scripts/create_source_zip.py
```
Output: `NUMM_Material_Code_Harmonization_Source.zip` (clean ~20 MB distribution package excluding `target/`, `node_modules/`, and heavy caches).
