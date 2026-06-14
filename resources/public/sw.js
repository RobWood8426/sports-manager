/**
 * sw.js — Service worker for SchoolScore PWA (SPO-46).
 *
 * Strategy:
 *   - Static assets (/css/, /js/, /icons/): cache-first, update in background.
 *   - Scorekeeper pages (GET /score/*): network-first; serve cached shell on failure.
 *   - Score event POSTs (POST /score/*/event): attempt network; on failure enqueue
 *     in IndexedDB and register a background sync.
 *   - Everything else: network-only.
 */

const CACHE_VERSION = 'v1';
const SHELL_CACHE = `schoolscore-shell-${CACHE_VERSION}`;
const STATIC_CACHE = `schoolscore-static-${CACHE_VERSION}`;

const SHELL_URLS = ['/score', '/score/offline'];
const STATIC_PATTERNS = [/\/css\//, /\/js\//, /\/icons\//];

// ---------------------------------------------------------------------------
// Install — cache the offline shell
// ---------------------------------------------------------------------------

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL_URLS)).catch(() => {})
  );
  self.skipWaiting();
});

// ---------------------------------------------------------------------------
// Activate — remove stale caches
// ---------------------------------------------------------------------------

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((k) => k !== SHELL_CACHE && k !== STATIC_CACHE)
          .map((k) => caches.delete(k))
      )
    )
  );
  self.clients.claim();
});

// ---------------------------------------------------------------------------
// Fetch
// ---------------------------------------------------------------------------

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Only intercept same-origin requests
  if (url.origin !== self.location.origin) return;

  // Score event POST — attempt network, queue on failure
  if (request.method === 'POST' && /\/score\/[^/]+\/event/.test(url.pathname)) {
    event.respondWith(handleScoreEvent(request));
    return;
  }

  // Static assets — cache-first
  if (STATIC_PATTERNS.some((p) => p.test(url.pathname))) {
    event.respondWith(cacheFirst(request, STATIC_CACHE));
    return;
  }

  // Scorekeeper GET pages — network-first, fall back to shell
  if (request.method === 'GET' && url.pathname.startsWith('/score')) {
    event.respondWith(networkFirstWithShell(request));
    return;
  }
});

// ---------------------------------------------------------------------------
// Background sync — replay queued events
// ---------------------------------------------------------------------------

self.addEventListener('sync', (event) => {
  if (event.tag === 'score-sync') {
    event.waitUntil(replayQueue());
  }
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function handleScoreEvent(request) {
  try {
    const response = await fetch(request.clone());
    if (response.ok) return response;
    // Non-2xx: queue the event
    await queueScoreEvent(request);
    return new Response('Queued', { status: 202 });
  } catch (_) {
    await queueScoreEvent(request);
    await registerSync();
    return new Response('Queued', { status: 202 });
  }
}

async function queueScoreEvent(request) {
  try {
    const body = await request.clone().formData();
    const entry = {
      clientId: crypto.randomUUID(),
      clientTs: new Date().toISOString(),
      url: request.url,
      method: request.method,
      fields: Object.fromEntries(body.entries()),
    };
    await idbEnqueue(entry);
  } catch (_) {}
}

async function registerSync() {
  try {
    const reg = await self.registration;
    await reg.sync.register('score-sync');
  } catch (_) {}
}

async function replayQueue() {
  const pending = await idbGetPending();
  for (const entry of pending) {
    try {
      const formData = new FormData();
      Object.entries(entry.fields || {}).forEach(([k, v]) => formData.append(k, v));
      const response = await fetch(entry.url, { method: entry.method, body: formData });
      if (response.ok) {
        await idbDequeue(entry.clientId);
      }
    } catch (_) {}
  }
}

async function cacheFirst(request, cacheName) {
  const cached = await caches.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok) {
    const cache = await caches.open(cacheName);
    cache.put(request, response.clone());
  }
  return response;
}

async function networkFirstWithShell(request) {
  try {
    const response = await fetch(request);
    if (response.ok) {
      const cache = await caches.open(SHELL_CACHE);
      cache.put(request, response.clone());
    }
    return response;
  } catch (_) {
    const cached = await caches.match(request);
    if (cached) return cached;
    const shell = await caches.match('/score');
    return shell || new Response('Offline', { status: 503 });
  }
}

// ---------------------------------------------------------------------------
// Minimal inline IndexedDB (no import in SW context)
// ---------------------------------------------------------------------------

const IDB_NAME = 'schoolscore';
const IDB_STORE = 'score-queue';

function idbOpen() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(IDB_NAME, 1);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains(IDB_STORE)) {
        db.createObjectStore(IDB_STORE, { keyPath: 'clientId' });
      }
    };
    req.onsuccess = (e) => resolve(e.target.result);
    req.onerror = (e) => reject(e.target.error);
  });
}

async function idbEnqueue(entry) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, 'readwrite');
    const req = tx.objectStore(IDB_STORE).put(entry);
    req.onsuccess = () => resolve();
    req.onerror = (e) => reject(e.target.error);
  });
}

async function idbDequeue(clientId) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, 'readwrite');
    const req = tx.objectStore(IDB_STORE).delete(clientId);
    req.onsuccess = () => resolve();
    req.onerror = (e) => reject(e.target.error);
  });
}

async function idbGetPending() {
  const db = await idbOpen();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, 'readonly');
    const req = tx.objectStore(IDB_STORE).getAll();
    req.onsuccess = (e) => {
      const events = e.target.result || [];
      events.sort((a, b) => (a.clientTs < b.clientTs ? -1 : 1));
      resolve(events);
    };
    req.onerror = (e) => reject(e.target.error);
  });
}
