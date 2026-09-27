# NUMM — Material Code Harmonization

The application source and documentation are in
[`Material Code Harmonization`](./Material%20Code%20Harmonization/README.md).

## Local start

```powershell
cd "Material Code Harmonization"
docker compose up -d --build
```

Open `http://localhost:3000` after the services become healthy.

## Hosted deployment

The root-level [`render.yaml`](./render.yaml) defines the React frontend, Spring Boot
backend, and Python matching service for Render. Follow the
[`Render + Supabase deployment guide`](./Material%20Code%20Harmonization/docs/RENDER_DEPLOYMENT.md)
for the required environment variables and verification steps.
