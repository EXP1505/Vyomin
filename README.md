# Vyomin

Vyomin is an intelligence and monitoring dashboard that brings together live flight telemetry, financial market data, and entity-relationship graph analysis in a single real-time interface.

## Features

- **Radar / Flight Telemetry** — live aircraft tracking rendered on a MapLibre map, streamed over WebSocket (STOMP/SockJS).
- **Finance Dashboard** — stock candle charts and telemetry for tracked companies, with a detail modal per stock.
- **Intelligence Graph** — force-directed graph view of relationships between companies, people, countries, and events, backed by Neo4j.
- **Auth** — JWT-based login and session handling.

## Tech Stack

**Backend** (`Backend/core-api`)
- Java 21, Spring Boot 4
- Spring Data JPA (PostgreSQL), Spring Data Neo4j, Spring Data Redis
- Spring Security + JWT (jjwt)
- Spring WebSocket

**Frontend** (`vyomin-web`)
- React 19 + Vite
- Tailwind CSS
- Zustand (state)
- Recharts (charts), MapLibre GL / react-map-gl (maps), react-force-graph-2d (graph view)
- STOMP over SockJS (real-time updates)

**Infrastructure**
- PostgreSQL, Neo4j, Redis
- Docker Compose for local orchestration
- Nginx (serving the built frontend)

## Project Structure

```
Vyomin/
├── Backend/
│   └── core-api/         # Spring Boot API (auth, finance, intelligence)
├── vyomin-web/           # React + Vite frontend
└── docker-compose.yml    # Postgres, Neo4j, Redis, core-api, vyomin-web
```

## Getting Started

### Prerequisites

- Docker & Docker Compose
- (For local dev without Docker) Java 21, Maven, and Node.js 18+

### Run with Docker Compose

1. Copy the environment templates and fill in your values:
   ```bash
   cp .env.compose.example .env.compose
   cp Backend/core-api/.env.example Backend/core-api/.env
   ```
   `Backend/core-api/.env` needs API keys for market/OSINT data providers (e.g. Finnhub, OpenSanctions) and default admin credentials.

2. Start all services:
   ```bash
   docker compose --env-file .env.compose up --build
   ```

3. Services:
   - Frontend: [http://localhost:3000](http://localhost:3000)
   - Backend API: [http://localhost:8080](http://localhost:8080)
   - Neo4j browser: [http://localhost:7474](http://localhost:7474)

### Run locally without Docker

**Backend**
```bash
cd Backend/core-api
./mvnw spring-boot:run
```

**Frontend**
```bash
cd vyomin-web
npm install
npm run dev
```

## Environment Variables

| File | Purpose |
|---|---|
| `.env.compose` | Postgres/Neo4j credentials used by `docker-compose.yml` |
| `Backend/core-api/.env` | API keys (Finnhub, OpenSanctions) and default admin login |
| `vyomin-web/.env` | Frontend-facing config (e.g. API base URL) |

See the corresponding `.example` files for the full list of required keys.

## License

TBD
