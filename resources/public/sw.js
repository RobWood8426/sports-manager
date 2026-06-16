/**
 * sw.js — Tombstone service worker (SPO-46).
 *
 * SchoolScore does NOT use a service worker. Offline score capture/replay is
 * handled entirely in-tab by js/sync-engine.js + js/score-queue.js (IndexedDB).
 *
 * This file exists only to clean up after earlier builds that registered a
 * caching service worker: any browser still running an old SW will fetch this
 * on its next update check, unregister itself, delete all caches, and reload
 * open clients so they get live network content. Once no browsers carry the old
 * SW, this file (and any registration call) can be removed entirely.
 */

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      try {
        const keys = await caches.keys();
        await Promise.all(keys.map((k) => caches.delete(k)));
      } catch (_) {}
      try {
        await self.registration.unregister();
      } catch (_) {}
      try {
        const clients = await self.clients.matchAll({ type: 'window' });
        clients.forEach((c) => c.navigate(c.url));
      } catch (_) {}
    })()
  );
});
