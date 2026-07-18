const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const { spawn, spawnSync } = require('child_process');

function repoRoot() {
  // demo-ui-e2e/scripts -> demo-ui-e2e -> repo root
  return path.resolve(__dirname, '..', '..');
}

function runOrThrow(cmd, args, options = {}) {
  const result = spawnSync(cmd, args, { stdio: 'inherit', ...options });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

async function getFreePort() {
  return await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        reject(new Error('Unexpected address type'));
        return;
      }
      const port = addr.port;
      server.close((err) => (err ? reject(err) : resolve(port)));
    });
  });
}

async function waitForOk(url, timeoutMs = 15_000) {
  const startedAt = Date.now();
  // Node 18+ has fetch.
  // eslint-disable-next-line no-constant-condition
  while (true) {
    try {
      const resp = await fetch(url, { method: 'GET' });
      if (resp.ok) return;
    } catch {}

    if (Date.now() - startedAt > timeoutMs) {
      throw new Error(`Timed out waiting for ${url}`);
    }
    await new Promise((r) => setTimeout(r, 150));
  }
}

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true });
}

function writeJson(p, obj) {
  fs.writeFileSync(p, JSON.stringify(obj, null, 2) + '\n', 'utf8');
}

function createFixtures(baseDir) {
  const examplesDir = path.join(baseDir, 'examples');
  const actionMetadataDir = path.join(baseDir, 'action-metadata');
  const paramsDir = path.join(baseDir, 'params');
  ensureDir(examplesDir);
  ensureDir(actionMetadataDir);
  ensureDir(paramsDir);

  writeJson(path.join(examplesDir, '01-alpha.json'), {
    example_id: 'alpha-example',
    shared: { query: 'alpha' },
    actions: [
      { action_id: 'A-003', label: 'Third' },
      { action_id: 'A-001', label: 'First' },
      { action_id: 'A-002', label: 'Second' },
    ],
  });

  writeJson(path.join(examplesDir, '02-beta.json'), {
    example_id: 'beta-example',
    shared: { context: 'beta' },
    actions: [{ action_id: 'B-001', label: 'Only' }],
  });

  const gammaJsonl = [
    {
      example_id: 'gamma-1',
      shared: { context: 'gamma' },
      actions: [{ action_id: 'G-001', label: 'One' }],
    },
    {
      example_id: 'gamma-2',
      shared: { context: 'gamma' },
      actions: [{ action_id: 'G-002', label: 'Two' }],
    },
  ]
    .map((o) => JSON.stringify(o))
    .join('\n');
  fs.writeFileSync(path.join(examplesDir, '03-gamma.jsonl'), gammaJsonl + '\n', 'utf8');

  writeJson(path.join(examplesDir, '04-delta.json'), {
    example_id: 'delta-empty-actions',
    shared: { context: 'delta' },
    actions: [],
  });

  const embeddedPng =
    'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlV8AAAAASUVORK5CYII=';
  const actionMetadataJsonl = ['A-001', 'A-002', 'A-003', 'B-001', 'G-001', 'G-002']
    .map((actionId) =>
      JSON.stringify({
        action_id: actionId,
        action_name: `Product ${actionId}`,
        action_image_url: embeddedPng,
        image_license: 'CC0-1.0',
      })
    )
    .join('\n');
  fs.writeFileSync(path.join(actionMetadataDir, 'metadata.jsonl'), actionMetadataJsonl + '\n', 'utf8');

  const algoParameters = {
    algorithm_name: 'demo-ranker',
    algorithm_version: '1.0.0',
    parameter_id: 'e2e-local',
    ran_at: new Date().toISOString(),
  };
  const algoParametersPath = path.join(paramsDir, 'algorithm-parameters.json');
  writeJson(algoParametersPath, algoParameters);

  const zipPath = path.join(paramsDir, 'demo-ranker-params.zip');
  const zipWorkDir = path.join(paramsDir, 'zipwork');
  ensureDir(path.join(zipWorkDir, 'demo-ranker'));
  fs.copyFileSync(algoParametersPath, path.join(zipWorkDir, 'demo-ranker', 'algorithm-parameters.json'));

  // Use the system zip (macOS/Linux). If unavailable, fail with a clear message.
  const zipCheck = spawnSync('zip', ['-v'], { stdio: 'ignore' });
  if (zipCheck.error) {
    throw new Error('Missing `zip` command; install it or adjust scripts/run-e2e.cjs to create the params zip differently.');
  }
  if (fs.existsSync(zipPath)) fs.rmSync(zipPath);
  runOrThrow('zip', ['-qr', zipPath, 'demo-ranker'], { cwd: zipWorkDir });

  return { examplesDir, actionMetadataDir, paramsZip: zipPath };
}

function findBuiltJar(globDir, prefix) {
  const dirents = fs.readdirSync(globDir, { withFileTypes: true });
  const files = dirents
    .filter((d) => d.isFile())
    .map((d) => d.name)
    .filter((n) => n.startsWith(prefix) && n.endsWith('-jar-with-dependencies.jar'));
  if (!files.length) return null;
  files.sort();
  return path.join(globDir, files[files.length - 1]);
}

function findBuiltPlainJar(globDir, prefix) {
  const dirents = fs.readdirSync(globDir, { withFileTypes: true });
  const files = dirents
    .filter((d) => d.isFile())
    .map((d) => d.name)
    .filter((n) => n.startsWith(prefix) && n.endsWith('.jar'))
    .filter((n) => !n.endsWith('-sources.jar'))
    .filter((n) => !n.endsWith('-javadoc.jar'))
    .filter((n) => !n.endsWith('-tests.jar'))
    .filter((n) => !n.endsWith('-jar-with-dependencies.jar'))
    .filter((n) => !n.startsWith('original-'));
  if (!files.length) return null;
  files.sort();
  return path.join(globDir, files[files.length - 1]);
}

async function main() {
  const root = repoRoot();
  const e2eDir = path.join(root, 'demo-ui-e2e');

  const tmpBase = fs.mkdtempSync(path.join(os.tmpdir(), 'hotvect-demo-ui-e2e-'));
  const { examplesDir, actionMetadataDir, paramsZip } = createFixtures(tmpBase);
  const port = await getFreePort();
  const baseURL = `http://127.0.0.1:${port}`;

  const buildNeeded = process.argv.includes('--build') || process.argv.includes('--rebuild');
  if (buildNeeded) {
    runOrThrow('mvn', ['-pl', 'hotvect-algorithm-demo,hotvect-integration-test', '-am', '-DskipTests', 'package'], {
      cwd: root,
    });
  }

  const uiJar = findBuiltJar(path.join(root, 'hotvect-algorithm-demo', 'target'), 'hotvect-algorithm-demo-');
  const algoJar = findBuiltPlainJar(path.join(root, 'hotvect-integration-test', 'target'), 'hotvect-integration-test-');
  if (!uiJar) throw new Error('Missing demo UI jar; run with --build to build it first.');
  if (!algoJar) throw new Error('Missing integration-test jar; run with --build to build it first.');

  const logFile = path.join(tmpBase, 'demo-ui.log');
  const logFd = fs.openSync(logFile, 'a');

  const server = spawn(
    'java',
    [
      '-jar',
      uiJar,
      '--algorithm-jar',
      algoJar,
      '--algorithm-name',
      'demo-ranker',
      '--parameter-path',
      paramsZip,
      '--ui',
      '--source-path',
      examplesDir,
      '--action-metadata-path',
      actionMetadataDir,
      '--host',
      '127.0.0.1',
      '--port',
      String(port),
    ],
    { stdio: ['ignore', logFd, logFd] }
  );

  const killServer = () => {
    if (server.exitCode !== null) return;
    server.kill('SIGTERM');
    setTimeout(() => {
      try {
        server.kill('SIGKILL');
      } catch {}
    }, 1_500).unref();
  };

  process.on('exit', () => {
    killServer();
    try {
      fs.closeSync(logFd);
    } catch {}
  });
  process.on('SIGINT', () => {
    killServer();
    process.exit(130);
  });

  try {
    await waitForOk(`${baseURL}/`);
  } catch (e) {
    killServer();
    throw new Error(`Demo UI failed to start. Log: ${logFile}\n${e.message}`);
  }

  const pwArgs = ['scripts/pw.cjs', 'test'];
  const pwResult = spawnSync('node', pwArgs, { cwd: e2eDir, stdio: 'inherit', env: { ...process.env, BASE_URL: baseURL } });
  killServer();

  if (pwResult.status !== 0) {
    console.error(`\nDemo UI log: ${logFile}`);
    process.exit(pwResult.status ?? 1);
  }
}

main().catch((e) => {
  console.error(e && e.stack ? e.stack : String(e));
  process.exit(1);
});
