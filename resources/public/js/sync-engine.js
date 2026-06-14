/**
 * sync-engine.js — Sync status & offline queue flush for SchoolScore (SPO-47).
 *
 * Reads fixture-id and scode-id from #sync-status data attributes.
 * Intercepts score-button form submissions to go through the queue rather than
 * a plain HTML POST, so offline events are stored and replayed on reconnect.
 *
 * Status values shown to the user: online | offline | syncing | synced | failed | conflict
 */

import { enqueue, dequeue, getPending } from './score-queue.js';

const statusEl = document.getElementById('sync-status');
const labelEl  = document.getElementById('sync-label');

if (!statusEl) {
  // Not on the live scoring page — do nothing.
  throw new Error('sync-engine loaded outside scoring page');
}

const fixtureId = statusEl.dataset.fixtureId;
const scodeId   = statusEl.dataset.scodeId;
const eventUrl  = `/score/${fixtureId}/event`;
const statusUrl = `/score/${fixtureId}/status`;

// ---------------------------------------------------------------------------
// Status display
// ---------------------------------------------------------------------------

const ICONS = {
  online:   '●',
  offline:  '○',
  syncing:  '↻',
  synced:   '✓',
  failed:   '✗',
  conflict: '⚠',
};

function setStatus(state, detail) {
  if (!labelEl) return;
  const icon = ICONS[state] || '●';
  labelEl.textContent = `${icon}  ${state}${detail ? ' — ' + detail : ''}`;
  statusEl.dataset.state = state;
}

// ---------------------------------------------------------------------------
// Send a single score event to the server
// ---------------------------------------------------------------------------

async function sendEvent(fields) {
  const body = new FormData();
  Object.entries(fields).forEach(([k, v]) => body.append(k, v));
  const res = await fetch(eventUrl, { method: 'POST', body });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

// ---------------------------------------------------------------------------
// Flush the offline queue
// ---------------------------------------------------------------------------

async function flushQueue() {
  const pending = await getPending();
  if (!pending.length) return;

  setStatus('syncing', `${pending.length} queued`);
  let failed = 0;

  for (const entry of pending) {
    try {
      const result = await sendEvent(entry.fields);
      if (result.status === 'ok') {
        await dequeue(entry.clientId);
        if (result.conflictDetected) setStatus('conflict');
      } else {
        failed++;
      }
    } catch (_) {
      failed++;
    }
  }

  if (failed > 0) {
    setStatus('failed', `${failed} unsynced`);
  } else {
    setStatus('synced');
    // Poll for conflict flag after flush
    pollStatus();
  }
}

// ---------------------------------------------------------------------------
// Poll server status (for conflict detection)
// ---------------------------------------------------------------------------

async function pollStatus() {
  try {
    const res = await fetch(statusUrl);
    if (!res.ok) return;
    const data = await res.json();
    if (data.conflictDetected) {
      setStatus('conflict', 'multiple scorers detected');
    }
  } catch (_) {}
}

// ---------------------------------------------------------------------------
// Intercept score-button form submissions
// ---------------------------------------------------------------------------

function interceptForms() {
  document.querySelectorAll('form[action="' + eventUrl + '"]').forEach((form) => {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const data = new FormData(form);
      const fields = Object.fromEntries(data.entries());
      const clientId = crypto.randomUUID();
      const clientTs = new Date().toISOString();
      fields['client-id'] = clientId;
      fields['client-ts'] = clientTs;

      if (!navigator.onLine) {
        setStatus('offline');
        await enqueue({ clientId, clientTs, url: eventUrl, method: 'POST', fields });
        return;
      }

      setStatus('syncing');
      try {
        const result = await sendEvent(fields);
        if (result.status === 'ok') {
          setStatus(result.conflictDetected ? 'conflict' : 'synced');
          // Reload to reflect updated score
          window.location.reload();
        } else {
          setStatus('failed');
          await enqueue({ clientId, clientTs, url: eventUrl, method: 'POST', fields });
        }
      } catch (_) {
        setStatus('offline');
        await enqueue({ clientId, clientTs, url: eventUrl, method: 'POST', fields });
      }
    });
  });
}

// ---------------------------------------------------------------------------
// Online / offline events
// ---------------------------------------------------------------------------

window.addEventListener('online', async () => {
  setStatus('syncing');
  await flushQueue();
  if (statusEl.dataset.state !== 'failed' && statusEl.dataset.state !== 'conflict') {
    setStatus('online');
  }
});

window.addEventListener('offline', () => setStatus('offline'));

// ---------------------------------------------------------------------------
// Init
// ---------------------------------------------------------------------------

interceptForms();
setStatus(navigator.onLine ? 'online' : 'offline');

// Flush any queue left from a previous session on load
if (navigator.onLine) {
  flushQueue().then(() => {
    if (statusEl.dataset.state === 'synced') setStatus('online');
  });
}

// Periodic conflict check every 15s
setInterval(pollStatus, 15_000);
