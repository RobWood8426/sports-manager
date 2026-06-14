/**
 * score-queue.js — IndexedDB-backed queue for offline score events (SPO-46).
 *
 * Each pending event is stored with a client-generated UUID and device
 * timestamp so ordering is preserved through reconnects and reloads.
 *
 * API:
 *   enqueue(event)   → Promise<id>
 *   dequeue(id)      → Promise<void>
 *   getPending()     → Promise<event[]>
 */

const DB_NAME = 'schoolscore';
const DB_VERSION = 1;
const STORE = 'score-queue';

function openDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'clientId' });
      }
    };
    req.onsuccess = (e) => resolve(e.target.result);
    req.onerror = (e) => reject(e.target.error);
  });
}

/**
 * Enqueue a pending score event. Adds `clientId` (UUID) and `clientTs`
 * (ISO timestamp) if not already present.
 */
export async function enqueue(event) {
  const db = await openDb();
  const entry = {
    clientId: event.clientId || crypto.randomUUID(),
    clientTs: event.clientTs || new Date().toISOString(),
    ...event,
  };
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite');
    const req = tx.objectStore(STORE).put(entry);
    req.onsuccess = () => resolve(entry.clientId);
    req.onerror = (e) => reject(e.target.error);
  });
}

/** Remove a successfully synced event from the queue. */
export async function dequeue(clientId) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite');
    const req = tx.objectStore(STORE).delete(clientId);
    req.onsuccess = () => resolve();
    req.onerror = (e) => reject(e.target.error);
  });
}

/** Return all pending events sorted by clientTs ascending. */
export async function getPending() {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, 'readonly');
    const req = tx.objectStore(STORE).getAll();
    req.onsuccess = (e) => {
      const events = e.target.result || [];
      events.sort((a, b) => (a.clientTs < b.clientTs ? -1 : 1));
      resolve(events);
    };
    req.onerror = (e) => reject(e.target.error);
  });
}
