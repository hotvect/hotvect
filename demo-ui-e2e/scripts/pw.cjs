const os = require('os');
const { spawnSync } = require('child_process');

function maybeFixPlaywrightHostPlatform() {
  if (process.env.PLAYWRIGHT_HOST_PLATFORM_OVERRIDE) return;
  if (os.platform() !== 'darwin') return;
  if (os.arch() !== 'arm64') return;

  // Workaround: Playwright 1.50.0 may mis-detect macOS ARM64 as mac-x64 on some systems.
  // This makes it look for `chrome-headless-shell-mac-x64` even when only ARM64 browsers are installed.
  const darwinKernelMajor = parseInt(os.release().split('.')[0] || '0', 10);
  if (!Number.isFinite(darwinKernelMajor) || darwinKernelMajor < 20) return;
  const lastStableMacOsMajor = 15;
  const macOsMajor = Math.min(darwinKernelMajor - 9, lastStableMacOsMajor);
  process.env.PLAYWRIGHT_HOST_PLATFORM_OVERRIDE = `mac${macOsMajor}-arm64`;
}

maybeFixPlaywrightHostPlatform();

const args = process.argv.slice(2);
const npxCmd = process.platform === 'win32' ? 'npx.cmd' : 'npx';
const result = spawnSync(npxCmd, ['playwright', ...args], { stdio: 'inherit', env: process.env });

process.exit(result.status ?? 1);
