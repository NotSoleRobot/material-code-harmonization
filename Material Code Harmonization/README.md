# NUMM — National Unified Material Master

A national material-master harmonizer for Indian CPSEs (ONGC, IOCL, GAIL, BHEL, SAIL).
Ingests messy legacy records, proposes matches via hybrid ML, routes them through
four-eyes governance, and mints a single authoritative national code.

## Quick start (Docker — recommended)

```powershell
docker compose up -d --build
```

Wait for all four containers to become healthy (~60s), then open `http://localhost:3000`.

| Service | Port | Notes |
|---------|------|-------|
| Frontend (nginx) | 3000 | React SPA |
| Backend (Spring Boot) | 8080 | REST API |
| Matching service (Flask) | 5000 | ML matching engine |
| PostgreSQL 16 | 5433 | Mapped to 5433 to avoid collision with native installs |

## Demo accounts

| Role | Email | Password |
|------|-------|----------|
| Administrator | `admin@numm.gov.in` | `admin123` |
| Senior reviewer | `senior.reviewer@numm.gov.in` | `reviewer123` |
| Mechanical reviewer | `reviewer.mech@numm.gov.in` | `reviewer123` |
| ONGC operator | `operator@ongc.co.in` | `operator123` |

## Submission-day preflight

Run this 10–15 minutes before the demo to wake the free-tier services and verify
the complete login, dashboard, catalog, and AI-comparison path:

```powershell
.\scripts\preflight_demo.ps1
```

Do not let the hosted services sit idle immediately before presenting. Render and
the hosted database can take more than a minute to resume after inactivity.

## Local development

```powershell
docker compose up -d postgres matching-service
cd material-master-backend
mvn spring-boot:run -Dspring-boot.run.profiles=python-matching,demo

# In a second terminal
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173/login`. This uses PostgreSQL and the real matching service.
The optional `demo` profile exposes quick-access cards for seeded accounts; it does
not bypass JWT authentication or substitute mock data.

## Documentation

- [RUNBOOK.md](docs/RUNBOOK.md) — ports, profiles, env vars, how everything connects
- [SYSTEM_STATUS_AND_ARCHITECTURE.md](docs/SYSTEM_STATUS_AND_ARCHITECTURE.md) — architecture overview
- [DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) — five-act demo walkthrough
- [RENDER_DEPLOYMENT.md](docs/RENDER_DEPLOYMENT.md) — hosted Render + Supabase deployment
