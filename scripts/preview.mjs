// AegisDesk — Freebuff preview server.
//
// Aegis is a native Android (Kotlin / Jetpack Compose) app: it cannot run in a
// browser, and the Gradle build needs JDK 17 + the Android SDK (see the GitHub
// Actions workflow). This tiny dependency-free Node server gives the Freebuff
// preview a useful page to show: project info, the build commands, the README,
// and a downloadable debug APK once one has been built.
//
// Usage:  PORT=4173 node scripts/preview.mjs
//         (Freebuff injects PORT and expects the server on 0.0.0.0)

import http from "node:http";
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, normalize, extname } from "node:path";

const PORT = Number(process.env.PORT) || 3000;
const HOST = "0.0.0.0";

const ROOT = process.cwd();
const APK_DIR = join(ROOT, "apps", "android", "build", "outputs", "apk", "debug");
const APK_PATH = join(APK_DIR, "app-debug.apk");
const README_PATH = join(ROOT, "README.md");
const MODEL_SETUP_PATH = join(ROOT, "MODEL_SETUP_S21.md");

const esc = (s) =>
  String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");

function apkEntry() {
  if (existsSync(APK_PATH)) {
    const size = statSync(APK_PATH).size;
    const mb = (size / (1024 * 1024)).toFixed(1);
    return `<div class="card">
      <div class="tag ok">APK READY</div>
      <h2>Debug APK available</h2>
      <p class="muted">Built at <code>apps/android/build/outputs/apk/debug/app-debug.apk</code> — ${mb} MB.</p>
      <a class="btn" href="/apk" download>Download app-debug.apk</a>
    </div>`;
  }
  return `<div class="card">
      <div class="tag">NO APK YET</div>
      <h2>Build the APK to download it</h2>
      <p class="muted">No APK found at <code>apps/android/build/outputs/apk/debug/app-debug.apk</code>.
      The Gradle build requires JDK 17 + Android SDK 35 — run <code>sh ./gradlew :apps:android:assembleDebug</code>
      on a machine with the Android toolchain (or via the repo's GitHub Actions workflow), then
      this page will offer the file here.</p>
    </div>`;
}

function renderIndex() {
  const apk = apkEntry();
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>AegisDesk — Preview</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    background: #0e0f0d;
    color: #e9e9e4;
    font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 48px 20px 80px;
  }
  .wrap { width: 100%; max-width: 720px; }
  header { display: flex; align-items: center; gap: 14px; margin-bottom: 8px; }
  .logo {
    width: 44px; height: 44px; border-radius: 12px;
    background: #f97316; color: #0e0f0d;
    display: grid; place-items: center;
    font-weight: 800; font-size: 20px;
  }
  h1 { font-size: 26px; margin: 0; letter-spacing: -0.02em; }
  .sub { color: #9a9a93; margin: 0 0 28px; }
  .badges { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 32px; }
  .badge {
    border: 1px solid #2c2d29; border-radius: 999px;
    padding: 5px 12px; font-size: 12px; color: #c5c5bd;
  }
  .card {
    background: #161713; border: 1px solid #262723; border-radius: 16px;
    padding: 22px; margin-bottom: 16px;
  }
  .card h2 { font-size: 16px; margin: 0 0 8px; }
  .card p { margin: 6px 0; font-size: 14px; line-height: 1.6; color: #cfcfc7; }
  .muted { color: #8f8f87 !important; font-size: 13px !important; }
  code {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    background: #20211d; border: 1px solid #2c2d29; border-radius: 6px;
    padding: 2px 6px; font-size: 12.5px; color: #d8d8d0;
  }
  pre {
    background: #12130f; border: 1px solid #2c2d29; border-radius: 10px;
    padding: 14px 16px; overflow-x: auto; font-size: 12.5px; line-height: 1.55;
  }
  .tag {
    display: inline-block; border-radius: 999px; padding: 3px 10px;
    font-size: 11px; font-weight: 700; letter-spacing: 0.06em;
    background: #262723; color: #c5c5bd; margin-bottom: 12px; text-transform: uppercase;
  }
  .tag.ok { background: #14532d; color: #4ade80; }
  .btn {
    display: inline-block; margin-top: 12px;
    background: #f97316; color: #0e0f0d; font-weight: 700; font-size: 14px;
    border-radius: 10px; padding: 10px 18px; text-decoration: none;
  }
  .btn:hover { background: #fb923c; }
  a.link { color: #fb923c; }
  ul { padding-left: 18px; font-size: 14px; color: #cfcfc7; line-height: 1.7; }
  footer { margin-top: 32px; font-size: 12px; color: #6c6c65; text-align: center; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo">A</div>
    <div>
      <h1>AegisDesk</h1>
      <p class="sub">Offline-first Android personal assistant — Kotlin / Jetpack Compose</p>
    </div>
  </header>

  <div class="badges">
    <span class="badge">Gradle 8.11.1</span>
    <span class="badge">AGP 8.7.3</span>
    <span class="badge">Kotlin 2.1.0</span>
    <span class="badge">compileSdk 35 · minSdk 26</span>
    <span class="badge">JDK 17</span>
  </div>

  <div class="card">
    <div class="tag">PREVIEW NOTE</div>
    <h2>Why this page instead of the app?</h2>
    <p>Aegis is a <strong>native Android app</strong> — it runs on a physical device (Android 8+)
    through the Accessibility Service and cannot render in a browser. This server exists so the
    Freebuff preview has a useful landing page: it documents the project, the build commands, and
    lets you download the debug APK once built.</p>
  </div>

  ${apk}

  <div class="card">
    <h2>Build it</h2>
    <p class="muted">Requires JDK 17 and Android SDK 35 (Android Studio syncs these). No env vars or API keys are needed.</p>
    <pre>sh ./gradlew :apps:android:assembleDebug
# APK → apps/android/build/outputs/apk/debug/app-debug.apk</pre>
    <p class="muted">Continuous build &amp; instrumented DB migration tests already run in CI: <a class="link" href="https://github.com/nadeemmurtaza/AegisDesk/actions">.github/workflows/android.yml</a></p>
  </div>

  <div class="card">
    <h2>Run it on a device</h2>
    <ul>
      <li>Install the APK on a physical Android 8+ phone.</li>
      <li>Open Aegis → <strong>Screen access</strong>, enable the service, and return.</li>
      <li>Optionally grant <strong>notification access</strong> (Inbox).</li>
      <li>Tap <strong>Import model</strong> and pick an official Gemma <code>.litertlm</code> pack (e.g. Gemma 3 1B INT4 on a Galaxy S21 8 GB) to enable offline AI chat.</li>
    </ul>
  </div>

  <div class="card">
    <h2>Docs</h2>
    <ul>
      <li><a class="link" href="/README.md">README.md</a></li>
      <li><a class="link" href="/MODEL_SETUP_S21.md">MODEL_SETUP_S21.md — Galaxy S21 model profile</a></li>
    </ul>
  </div>

  <footer>Preview server for AegisDesk · no data leaves the device · offline-first</footer>
</div>
</body>
</html>`;
}

function serveFile(res, filePath, contentType) {
  const body = readFileSync(filePath);
  res.writeHead(200, {
    "Content-Type": contentType,
    "Content-Length": body.length,
    "Cache-Control": "no-store",
  });
  res.end(body);
}

const server = http.createServer((req, res) => {
  const url = (req.url || "/").split("?")[0];

  try {
    if (url === "/" || url === "/index.html") {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(renderIndex());
    } else if (url === "/README.md" && existsSync(README_PATH)) {
      serveFile(res, README_PATH, "text/markdown; charset=utf-8");
    } else if (url === "/MODEL_SETUP_S21.md" && existsSync(MODEL_SETUP_PATH)) {
      serveFile(res, MODEL_SETUP_PATH, "text/markdown; charset=utf-8");
    } else if (url === "/apk" && existsSync(APK_PATH)) {
      serveFile(res, APK_PATH, "application/vnd.android.package-archive");
    } else if (url === "/healthz") {
      res.writeHead(200, { "Content-Type": "text/plain" });
      res.end("ok");
    } else {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("Not found");
    }
  } catch (err) {
    res.writeHead(500, { "Content-Type": "text/plain" });
    res.end(`Server error: ${err.message}`);
  }
});

server.listen(PORT, HOST, () => {
  console.log(`[aegis-preview] listening on http://${HOST}:${PORT}`);
});
