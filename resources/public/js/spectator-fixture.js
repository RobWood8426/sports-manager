/**
 * spectator-fixture.js — Live score polling for the spectator fixture detail page (SPO-54).
 *
 * Reads the fixture UUID from the script tag's data-fixture-id attribute.
 * Polls GET /e/fixture/:fid/score every 10 seconds and updates the DOM.
 * Stops polling when the server reports a finalStatus.
 */

const scoreDisplay = document.getElementById('score-display');
const fixtureId    = scoreDisplay && scoreDisplay.dataset.fixtureId;

const scoreA     = document.getElementById('score-a');
const scoreB     = document.getElementById('score-b');
const lastUpdate = document.getElementById('last-updated');
const statusEl   = document.getElementById('fixture-status');

if (!fixtureId || !scoreA || !scoreB) {
  throw new Error('spectator-fixture: missing fixture-id or score elements');
}

const scoreUrl = `/e/fixture/${fixtureId}/score`;
let   pollTimer = null;

function formatTime(isoStr) {
  if (!isoStr) return '';
  const d = new Date(isoStr);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

async function poll() {
  try {
    const res = await fetch(scoreUrl, { cache: 'no-store' });
    if (!res.ok) return;
    const data = await res.json();

    scoreA.textContent = data.a ?? 0;
    scoreB.textContent = data.b ?? 0;

    if (data.finalStatus) {
      const label = {
        accepted: 'Final Score',
        disputed: 'Disputed',
        pending:  'Pending',
      }[data.finalStatus] ?? data.finalStatus;
      if (statusEl) statusEl.textContent = label;
      if (lastUpdate) lastUpdate.textContent = 'Score finalised.';
      clearInterval(pollTimer);
      return;
    }

    if (lastUpdate && data.lastRecordedAt) {
      lastUpdate.textContent = `Last updated ${formatTime(data.lastRecordedAt)}`;
    }
  } catch (_) {
    // Network error — silent; next tick will retry.
  }
}

// Initial fetch immediately, then every 3 s.
poll();
pollTimer = setInterval(poll, 3_000);
