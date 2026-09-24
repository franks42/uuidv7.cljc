#!/usr/bin/env node
// Scittle smoke test against the library as published on jsdelivr.
//
// Usage: node test/runners/run-scittle-cdn.mjs [ref]
//   ref defaults to "main"; pass a release tag such as v0.6.0 to test
//   what the README tells users to load. For a release tag the loaded
//   library must also report that version.
//
// Only the page and test/published/published_smoke.cljs are served locally; the
// library comes from cdn.jsdelivr.net.

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";

const ROOT = new URL("../..", import.meta.url).pathname;
const TIMEOUT_MS = 60_000;
const REF = process.argv[2] || "main";
if (!/^[A-Za-z0-9._-]+$/.test(REF)) {
  console.error(`Invalid git ref: ${REF}`);
  process.exit(2);
}
const LIB = `https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@${REF}` +
            "/src/com/github/franks42/uuidv7/core.cljc";

// Same Scittle release as test/runners/test_scittle/index.html
const PAGE = `<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<script src="https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/scittle.js"></script>
</head><body>
<script type="application/x-scittle" src="${LIB}"></script>
<script type="application/x-scittle" src="/smoke.cljs"></script>
</body></html>`;

function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      const path = new URL(req.url, "http://localhost").pathname;
      if (path === "/") {
        res.writeHead(200, { "Content-Type": "text/html" });
        res.end(PAGE);
      } else if (path === "/smoke.cljs") {
        res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
        res.end(await readFile(`${ROOT}test/published/published_smoke.cljs`));
      } else {
        res.writeHead(404);
        res.end();
      }
    });
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}

async function run() {
  const server = await startServer();
  let browser;
  try {
    console.log(`Library: ${LIB}`);
    // CHROME_PATH: use an installed Chrome instead of Playwright's download
    // (CI does this; the download stalls on GitHub runners).
    browser = await chromium.launch({
      headless: true,
      ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}),
    });
    const page = await browser.newPage();
    const outcome = new Promise((resolve) => {
      page.on("console", (msg) => {
        const text = msg.text();
        console.log(text);
        const ok = text.match(/^SMOKE OK (\S+)/);
        if (ok) resolve({ ok: true, version: ok[1] });
        if (text.includes("SMOKE FAIL")) resolve({ ok: false });
      });
      page.on("pageerror", (err) => {
        console.error(err.message);
        resolve({ ok: false });
      });
      setTimeout(() => resolve({ ok: false, timeout: true }), TIMEOUT_MS);
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const { ok, version, timeout } = await outcome;
    if (timeout) console.error(`No result within ${TIMEOUT_MS / 1000} s`);
    if (!ok) process.exitCode = 1;
    if (ok && /^v\d+\.\d+\.\d+$/.test(REF) && version !== REF.slice(1)) {
      console.error(`@${REF} served uuidv7 ${version}, expected ${REF.slice(1)}`);
      process.exitCode = 1;
    }
  } catch (err) {
    console.error("Scittle CDN runner error:", err.message);
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
    server.close();
  }
}

run();
