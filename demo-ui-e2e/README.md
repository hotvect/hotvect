# Playwright E2E (local)

This folder contains a tiny Playwright test suite for the Demo UI.

## Prereqs
- Node.js 18+ (any package manager is fine; examples use npm)
- The Demo UI running locally (default: `http://127.0.0.1:13000`)

## Install
```bash
cd demo-ui-e2e
npm ci
npm run install-browsers
```

## Run tests
```bash
# If your demo UI is on the default port:
npm test

# If the demo UI runs elsewhere:
BASE_URL=http://127.0.0.1:12000 npm test

# Interactive runner:
npm run test:ui
```

## Self-contained run (starts its own demo server)
This creates a temp fixture dataset + params zip, starts the demo UI on a free port, runs tests, then stops the server.

```bash
cd demo-ui-e2e
npm run e2e

# If jars are not built yet:
npm run e2e -- --build
```
