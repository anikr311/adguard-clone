import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT) || 3000;

const MIME_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".mjs": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".gif": "image/gif",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".mp4": "video/mp4",
};

const server = http.createServer((req, res) => {
  const reqUrl = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
  let pathname = decodeURIComponent(reqUrl.pathname);

  if (pathname === "/" || pathname === "/en" || pathname === "/en/") {
    pathname = "/index.html";
  }

  // Mock telemetry/analytics endpoints gracefully so the frontend never logs 404s
  if (pathname.startsWith("/-/logs") || pathname.startsWith("/-/metrics") || pathname === "/click.json") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  const safePath = path.normalize(path.join(__dirname, pathname));
  if (!safePath.startsWith(__dirname)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  let targetFile = safePath;
  if (fs.existsSync(targetFile) && fs.statSync(targetFile).isDirectory()) {
    targetFile = path.join(targetFile, "index.html");
  }
  if (!fs.existsSync(targetFile)) {
    // Fallback to index.html for SPA/locale routes
    if (pathname.endsWith(".html")) {
      targetFile = path.join(__dirname, "index.html");
    } else {
      res.writeHead(404);
      res.end("Not found");
      return;
    }
  }

  const ext = path.extname(targetFile).toLowerCase();
  const contentType = MIME_TYPES[ext] || "application/octet-stream";

  res.writeHead(200, {
    "Content-Type": contentType,
    "Access-Control-Allow-Origin": "*",
    "Cache-Control": "public, max-age=3600",
  });
  fs.createReadStream(targetFile).pipe(res);
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`AdGuard 1:1 local server running at http://127.0.0.1:${PORT}/en/welcome.html`);
});
