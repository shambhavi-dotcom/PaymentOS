# PaymentOS dashboard

React, TypeScript, Vite, Tailwind CSS, Recharts, and Lucide React operations console.

## Run locally

1. Start PostgreSQL and the backend from the repository root:

   ```powershell
   docker compose up --build postgres backend
   ```

   The backend is available at `http://localhost:8080`.

2. Start the frontend:

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

   Open the Vite URL (normally `http://localhost:5173`). The Vite development server proxies `/api` calls to `http://localhost:8080`.

## Dashboard data and controls

- Overview, transactions, processor health, and payment attempts read from the backend APIs.
- The simulation lab posts generated payments to `POST /api/simulations/run`; optional failure injection updates the selected processor's simulator configuration.
- Performance analytics are grouped from persisted processor attempts by the routing strategy saved with each payment. Empty results are shown until attempts exist.
- Unknown payments can be reconciled from the payment detail drawer. The backend simulator resolves the original processor request ID; the browser does not choose the final status.

The Vite proxy is for local development. A production deployment must serve the built frontend behind a same-origin reverse proxy for `/api`, or configure an equivalent deployment proxy.
