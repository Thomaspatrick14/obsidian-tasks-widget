// Sets version info (product name, version) on the built Windows exe.
// The icon is left as Electron's default.
// electron-builder needs wine for this on Linux; under WSL we can run
// rcedit-x64.exe directly instead. On Windows it runs natively.
const { execFileSync } = require('child_process');
const path = require('path');
const pkg = require('../package.json');

const win = (p) => (process.platform === 'win32' ? p : execFileSync('wslpath', ['-w', p]).toString().trim());
const rcedit = path.join(__dirname, '..', 'node_modules', 'rcedit', 'bin', 'rcedit-x64.exe');
const exe = path.join(__dirname, '..', 'dist', 'win-unpacked', `${pkg.build.productName}.exe`);

execFileSync(rcedit, [
  win(exe),
  '--set-version-string', 'ProductName', pkg.build.productName,
  '--set-version-string', 'FileDescription', pkg.build.productName,
  '--set-version-string', 'CompanyName', pkg.author,
  '--set-file-version', pkg.version,
  '--set-product-version', pkg.version,
], { stdio: 'inherit' });
console.log('  • set version info on', path.basename(exe));
