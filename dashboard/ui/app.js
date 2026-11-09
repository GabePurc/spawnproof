'use strict';

// The dashboard's window. Everything it shows comes from the daemon: the live status arrives as a "status" event every
// second, and the projects, a project's runs and one run's results are asked for (the app's "daemon" command passes the
// request on). Text from results is always put in as text, never as HTML: test names and messages come from mods' code.

const tauri = window.__TAURI__;

const state = {
	status: null,
	offline: null,
	projects: [],
	view: { name: 'live' },
	/** The view's data once loaded: a project's runs, or a run. */
	loaded: null,
	/** The newest game in the status's recent list: when it changes, a run may have been kept. */
	lastGame: null,
};

function ask(request) {
	return tauri.core.invoke('daemon', { request });
}

// ---- Building the page.

/** An element: h('div', {class: 'row', onclick: f}, 'text', child, [more children]). */
function h(tag, props, ...children) {
	const el = document.createElement(tag);
	for (const [key, value] of Object.entries(props || {})) {
		if (value === null || value === undefined || value === false) continue;
		if (key === 'class') el.className = value;
		else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
		else if (key === 'open' || key === 'hidden') el[key] = Boolean(value);
		else if (key === 'style') el.style.cssText = value; // Not as an attribute, which the page's security policy turns away.
		else el.setAttribute(key, value);
	}
	append(el, children);
	return el;
}

function append(el, children) {
	for (const child of children) {
		if (child === null || child === undefined || child === false) continue;
		if (Array.isArray(child)) append(el, child);
		else el.append(child instanceof Node ? child : document.createTextNode(String(child)));
	}
}

function replace(el, ...children) {
	el.replaceChildren();
	append(el, children);
}

function pill(status, text) {
	return h('span', { class: 'pill ' + (status || '') }, text || statusWord(status));
}

function statusWord(status) {
	return { 'not-run': 'not run' }[status] || status || '?';
}

// ---- Words for times and things.

function duration(ms) {
	const seconds = Math.max(0, Math.floor((ms || 0) / 1000));
	if (seconds < 60) return seconds + 's';
	const minutes = Math.floor(seconds / 60);
	if (minutes < 60) return minutes + 'm ' + String(seconds % 60).padStart(2, '0') + 's';
	return Math.floor(minutes / 60) + 'h ' + String(minutes % 60).padStart(2, '0') + 'm';
}

function ago(millis) {
	if (!millis) return 'never';
	const ms = Date.now() - millis;
	if (ms < 60_000) return 'just now';
	if (ms < 3_600_000) return Math.floor(ms / 60_000) + 'm ago';
	if (ms < 86_400_000) return Math.floor(ms / 3_600_000) + 'h ago';
	const days = Math.floor(ms / 86_400_000);
	if (days < 7) return days === 1 ? 'yesterday' : days + ' days ago';
	return new Date(millis).toLocaleDateString();
}

function agoIso(iso) {
	const t = Date.parse(iso);
	return isNaN(t) ? '?' : ago(t);
}

function plural(n, word) {
	return n + ' ' + word + (n === 1 ? '' : 's');
}

/** A folder, with a home folder as ~. */
function tilde(dir) {
	if (!dir) return '';
	const home = /^\/(home|Users)\/[^/]+/.exec(dir);
	return home ? '~' + dir.slice(home[0].length) : dir;
}

/** "horsing-around :26.3:runClientGameTest (100-hats)". */
function who(entry) {
	const where = entry.label || entry.branch;
	return entry.task + (where ? ' (' + where + ')' : '');
}

/** "3 passed, 1 failed" from a run's counts. */
function countsText(counts) {
	if (!counts) return '';
	const parts = [];
	if (counts.passed) parts.push(counts.passed + ' passed');
	if (counts.failed) parts.push(counts.failed + ' failed');
	if (counts.errors) parts.push(plural(counts.errors, 'error'));
	if (counts.notRun) parts.push(counts.notRun + ' not run');
	return parts.join(', ') || plural(counts.tests || 0, 'test');
}

function checkoutName(c) {
	if (c.watch) return 'Pull requests (' + c.watch + ')';
	return c.label || c.branch || tilde(c.dir);
}

// ---- Moving around.

function show(view) {
	state.view = view;
	state.loaded = null;
	document.querySelector('main').scrollTop = 0;
	render();
	if (view.name === 'project') loadProject(view);
	if (view.name === 'run') loadRun(view);
}

function render() {
	renderSidebar();
	renderOffline();
	const view = document.getElementById('view');
	if (state.view.name === 'live') replace(view, live());
	else if (state.view.name === 'project') replace(view, project());
	else if (state.view.name === 'run') replace(view, run());
}

function renderOffline() {
	const banner = document.getElementById('offline');
	banner.hidden = !state.offline;
	if (state.offline) banner.textContent = state.offline.message;
}

function renderSidebar() {
	const s = state.status;
	const liveButton = document.getElementById('nav-live');
	liveButton.classList.toggle('selected', state.view.name === 'live');
	const badge = document.getElementById('live-badge');
	const busy = s ? s.running.length + s.waiting.length : 0;
	badge.hidden = busy === 0;
	badge.textContent = s && s.waiting.length ? s.running.length + ' · ' + s.waiting.length : String(busy);
	badge.title = s ? plural(s.running.length, 'game') + ' running, ' + s.waiting.length + ' waiting' : '';

	const list = document.getElementById('nav-projects');
	const selected = state.view.project;
	if (!state.projects.length) {
		replace(list, h('div', { class: 'nav-empty' }, 'None yet: a project shows up once its games run.'));
	} else {
		replace(list, state.projects.map(p => h('button', {
			class: 'nav-item' + (selected === p.name ? ' selected' : ''),
			onclick: () => show({ name: 'project', project: p.name }),
			title: p.name,
		},
		h('span', { class: 'dot ' + (p.lastRun ? p.lastRun.status : '') }),
		h('span', { class: 'nav-label' }, p.name,
			h('span', { class: 'nav-sub' }, p.lastRun ? 'last run ' + agoIso(p.lastRun.endedAt) : 'no test runs kept')))));
	}

	const info = document.getElementById('daemon-info');
	info.textContent = s ? 'Spawnproof ' + s.version + ' · up ' + duration(s.now - s.startedAt) : '';
}

// ---- Live: the queue as it is now.

function live() {
	const s = state.status;
	if (!s) return h('div', { class: 'empty' }, 'Waiting for Spawnproof…');
	const now = s.now;
	const d = s.display || {};
	const display = d.up ? 'hidden display ' + d.name + ' (' + d.kind + (d.renderer ? ', ' + d.renderer : '') + ')'
		: 'hidden display ' + d.mode + (d.note ? ': ' + d.note : '');
	return [
		h('div', { class: 'header' }, h('h1', {}, 'Live'), s.paused ? pill('waiting', 'paused') : null),
		h('div', { class: 'subtitle' }, plural(s.slots, 'game slot') + ' · ' + display),
		s.paused ? h('div', { class: 'card notes problem' }, h('p', {}, 'Paused: running games finish, and no new ones start until you resume (spawnproof resume).')) : null,

		s.playing.length ? [
			h('h2', {}, 'Playing' + (s.holdWhilePlaying ? ': test games wait until the game closes' : '')),
			h('div', { class: 'card list' }, s.playing.map(l => gameRow(l, now, 'playing'))),
		] : null,

		h('h2', {}, 'Running'),
		h('div', { class: 'card list' }, s.running.length ? s.running.map(l => gameRow(l, now, 'running')) : h('div', { class: 'empty' }, 'No games running.')),

		s.waiting.length ? [
			h('h2', {}, 'Waiting' + (s.waiting[0].reason ? ': ' + s.waiting[0].reason : '')),
			h('div', { class: 'card list' }, s.waiting.map((l, i) => gameRow(l, now, 'waiting', i + 1))),
		] : null,

		(s.watching || []).length ? [h('h2', {}, 'Pull requests tested here'), s.watching.map(r => watched(r, now))] : null,

		(s.recent || []).length ? [
			h('h2', {}, 'Recently'),
			h('div', { class: 'card list' }, s.recent.map(g => h('div', { class: 'row' },
				h('div', { class: 'main' },
					h('div', { class: 'title' }, g.project + ' ' + who(g)),
					h('div', { class: 'meta' }, '#' + g.id + ' · ' + duration(g.durationMs) + (g.waitedMs >= 1000 ? ' after waiting ' + duration(g.waitedMs) : ''))),
				h('div', { class: 'side' }, 'ended ' + ago(g.endedAt))))),
		] : null,
	];
}

function gameRow(l, now, kind, position) {
	const since = kind === 'waiting' ? l.queuedAt : l.startedAt;
	const elapsed = now - (since || now);
	const meta = ['#' + l.id, duration(elapsed) + (kind === 'waiting' ? ' waiting' : '')];
	if (l.exclusive) meta.push('alone');
	if (l.display) meta.push('hidden on ' + l.display);
	let progress = null;
	if (kind === 'running' && l.estimateMs > 0) {
		const over = elapsed > l.estimateMs;
		meta.push(over ? 'running longer than usual (' + duration(l.estimateMs) + ')' : 'about ' + duration(l.estimateMs - elapsed) + ' left');
		progress = h('div', { class: 'progress' + (over ? ' over' : '') }, h('div', { style: 'width: ' + Math.min(100, 100 * elapsed / l.estimateMs).toFixed(1) + '%' }));
	}
	return h('div', { class: 'row' },
		kind === 'waiting' ? h('span', { class: 'pill waiting' }, position === 1 ? 'next' : '#' + position + ' in line') : pill(kind),
		h('div', { class: 'main' },
			h('div', { class: 'title' }, l.project + ' ' + who(l)),
			h('div', { class: 'meta' }, meta.join(' · ')),
			progress));
}

function watched(r, now) {
	const rows = [];
	if (r.testing && r.testing.pr) {
		rows.push(h('div', { class: 'row' }, pill('testing'),
			h('div', { class: 'main' },
				h('div', { class: 'title' }, '#' + r.testing.pr + ' ' + (r.testing.title || '')),
				h('div', { class: 'meta' }, shortSha(r.testing.sha) + ' · ' + duration(now - r.testing.startedAt)))));
	}
	for (const pr of r.waiting || []) rows.push(h('div', { class: 'row' }, pill('waiting'), h('div', { class: 'main' }, h('div', { class: 'title' }, '#' + pr))));
	for (const p of r.prs || []) {
		if (p.state === 'testing' || p.state === 'waiting') continue;
		const summary = p.state === 'interrupted' ? 'not finished when Spawnproof stopped: tested again' : p.summary || '';
		rows.push(h('div', { class: 'row' }, pill(p.state),
			h('div', { class: 'main' },
				h('div', { class: 'title' }, '#' + p.pr + ' ' + (p.title || '')),
				h('div', { class: 'meta' }, summary)),
			h('div', { class: 'side' }, shortSha(p.sha) + (p.at ? ' · ' + ago(p.at) : ''))));
	}
	return [
		h('div', { class: 'header' }, h('h3', {}, r.repo), h('span', { class: 'muted' }, r.lookedAt ? 'looked at GitHub ' + ago(r.lookedAt) : '')),
		r.problem ? h('div', { class: 'card notes problem' }, h('p', {}, r.problem)) : null,
		h('div', { class: 'card list', style: 'margin: 10px 0 18px' }, rows.length ? rows : h('div', { class: 'empty' }, 'No open pull requests to test.')),
	];
}

function shortSha(sha) {
	return sha ? sha.slice(0, 7) : '?';
}

// ---- A project: its checkouts and kept runs.

async function loadProject(view) {
	try {
		const reply = await ask({ type: 'runs', project: view.project, limit: 200 });
		if (state.view !== view) return;
		state.loaded = { runs: reply.runs };
	} catch (e) {
		if (state.view !== view) return;
		state.loaded = { error: String(e) };
	}
	render();
}

function project() {
	const view = state.view;
	const p = state.projects.find(x => x.name === view.project) || { name: view.project, checkouts: [] };
	const loaded = state.loaded;
	const runs = loaded && loaded.runs ? loaded.runs.filter(r => !view.dir || r.dir === view.dir) : [];
	return [
		h('div', { class: 'header' }, h('h1', {}, p.name)),
		h('div', { class: 'subtitle' }, plural(p.runs || 0, 'test run') + ' kept · ' + plural(p.checkouts.length, 'checkout')),
		p.checkouts.length > 1 ? h('div', { class: 'chips' },
			h('button', { class: 'chip' + (!view.dir ? ' selected' : ''), onclick: () => { view.dir = null; render(); } }, 'Every checkout'),
			p.checkouts.map(c => h('button', {
				class: 'chip' + (view.dir === c.dir ? ' selected' : ''),
				title: c.dir,
				onclick: () => { view.dir = c.dir; render(); },
			}, checkoutName(c)))) : null,
		h('h2', {}, 'Test runs'),
		!loaded ? h('div', { class: 'empty' }, 'Loading…')
			: loaded.error ? h('div', { class: 'card notes problem' }, h('p', {}, loaded.error))
			: h('div', { class: 'card list' }, runs.length ? runs.map(runRow) : h('div', { class: 'empty' },
				'No test runs kept yet. A run of this project\'s tests (spawnproof test, or its game test task) shows up here when it ends.')),
	];
}

function runRow(r) {
	const meta = [agoIso(r.endedAt), duration(r.durationMs), r.how];
	if (r.screenshots) meta.push(plural(r.screenshots, 'screenshot'));
	return h('div', { class: 'row clickable', onclick: () => show({ name: 'run', id: r.id, project: r.project }) },
		pill(r.status),
		h('div', { class: 'main' },
			h('div', { class: 'title' }, who(r)),
			h('div', { class: 'meta' }, meta.filter(Boolean).join(' · ')),
			r.notPassed && r.notPassed.length ? h('div', { class: 'meta' }, 'Not passed: ' + r.notPassed.join(', ')) : null),
		h('div', { class: 'side' }, countsText(r.counts)));
}

// ---- One run: its tests, checks and screenshots.

async function loadRun(view) {
	try {
		const reply = await ask({ type: 'run', id: view.id });
		if (state.view !== view) return;
		state.loaded = reply;
	} catch (e) {
		if (state.view !== view) return;
		state.loaded = { error: String(e) };
	}
	render();
}

function run() {
	const view = state.view;
	const back = h('button', { class: 'back', onclick: () => show({ name: 'project', project: view.project }) }, '← ' + view.project);
	const loaded = state.loaded;
	if (!loaded) return [back, h('div', { class: 'empty' }, 'Loading…')];
	if (loaded.error) return [back, h('div', { class: 'card notes problem' }, h('p', {}, loaded.error))];
	const meta = loaded.run;
	const results = loaded.results;
	const counts = results.counts || {};
	const clock = results.clock || {};
	const tests = results.tests || [];
	const where = [meta.project, meta.label || meta.branch, tilde(meta.dir)].filter(Boolean).join(' · ');
	return [
		back,
		h('div', { class: 'header' }, h('h1', {}, meta.task), pill(results.status)),
		h('div', { class: 'subtitle' }, where),
		h('div', { class: 'subtitle' }, 'Ran ' + agoIso(results.startedAt) + ' for ' + duration(results.durationMs)
			+ (meta.how === 'one game' ? ', in one game' : (results.games || []).length > 1 ? ', across ' + results.games.length + ' games' : '')),
		h('div', { class: 'stats' },
			stat(counts.tests || tests.length, 'tests'),
			stat(counts.passed || 0, 'passed'),
			stat(counts.failed || 0, 'failed'),
			stat(counts.errors || 0, 'errors'),
			counts.notRun ? stat(counts.notRun, 'not run') : null,
			counts.checks ? stat(counts.failedChecks ? counts.failedChecks + ' of ' + counts.checks : counts.checks, counts.failedChecks ? 'checks failed' : 'checks') : null,
			clock.ticksPerSecond ? stat(clock.ticksPerSecond, 'ticks a second' + (clock.mode === 'frames' ? ' (frame clock)' : '')) : null),
		(results.problems || []).length ? h('div', { class: 'card notes problem' }, results.problems.map(p => h('p', {}, p))) : null,
		(results.notes || []).length ? h('div', { class: 'card notes' }, results.notes.map(p => h('p', {}, p))) : null,
		h('h2', {}, 'Tests'),
		tests.length ? tests.map(t => test(t, meta.id)) : h('div', { class: 'card empty' }, 'No tests ran.'),
		(results.games || []).length > 1 ? [
			h('h2', {}, 'Games'),
			h('div', { class: 'card' }, h('table', {},
				h('tr', {}, h('th', {}, 'Game'), h('th', {}, 'Tests'), h('th', {}, 'Time'), h('th', {}, 'Ended')),
				results.games.map(g => h('tr', {},
					h('td', {}, String(g.game)),
					h('td', {}, (g.tests || []).join(', ') || 'none'),
					h('td', {}, duration(g.durationMs)),
					h('td', {}, g.stopped ? g.stopped : g.exitCode === 0 ? 'on its own' : 'exit code ' + g.exitCode))))),
		] : null,
	];
}

function stat(value, label) {
	return h('div', { class: 'card stat' }, h('div', { class: 'value' }, String(value)), h('div', { class: 'label' }, label));
}

function test(t, runId) {
	const checks = t.checks || [];
	const failed = checks.filter(c => !c.ok).length;
	const side = [];
	if (checks.length) side.push(failed ? failed + ' of ' + plural(checks.length, 'check') + ' failed' : plural(checks.length, 'check'));
	if (t.durationMs !== undefined) side.push(duration(t.durationMs));
	if (t.game) side.push('game ' + t.game);
	return h('details', { class: 'card test', open: t.status !== 'passed' },
		h('summary', {},
			pill(t.status),
			h('div', { class: 'main' }, h('div', { class: 'title' }, t.name), t.tags && t.tags.length ? h('div', { class: 'meta' }, t.tags.join(', ')) : null),
			h('div', { class: 'side' }, side.join(' · '))),
		h('div', { class: 'test-body' },
			t.error ? h('div', { class: 'error-box' },
				h('div', {}, h('strong', {}, t.error.type || 'Error'), ': ' + (t.error.message || '')),
				t.error.stack ? h('details', { class: 'more' }, h('summary', {}, 'Stack trace'), h('pre', {}, t.error.stack)) : null) : null,
			t.stopReason ? h('div', { class: 'error-box' }, t.stopReason) : null,
			checks.length ? h('ul', { class: 'checks' }, checks.map(check)) : null,
			(t.notes || []).length ? h('div', { class: 'card notes' }, t.notes.map(n => h('p', {}, (n.section ? n.section + ': ' : '') + (n.text || n)))) : null,
			(t.screenshots || []).length ? h('div', { class: 'shots' }, t.screenshots.map(s => screenshot(s, runId, t.name))) : null,
			(t.files || []).length ? h('div', { class: 'muted', style: 'margin-top: 10px' }, 'Files: ' + t.files.map(f => f.name + ' (' + f.path + ')').join(', ')) : null,
			(t.log || []).length ? h('details', { class: 'more' }, h('summary', {}, 'Log (' + plural(t.log.length, 'line') + (t.logDropped ? ', ' + t.logDropped + ' left out' : '') + ')'),
				h('pre', {}, t.log.map(line => typeof line === 'string' ? line : JSON.stringify(line)).join('\n'))) : null));
}

function check(c) {
	let detail = null;
	if (c.value !== undefined) detail = c.value + ' (from ' + c.min + ' to ' + c.max + ')';
	else if (c.expected !== undefined) detail = c.ok ? c.actual : 'expected ' + c.expected + ', got ' + c.actual;
	if (c.detail) detail = (detail ? detail + ': ' : '') + c.detail;
	return h('li', { class: 'check ' + (c.ok ? 'ok' : 'bad') },
		h('span', { class: 'mark' }, c.ok ? '✓' : '✗'),
		h('div', {},
			h('div', {}, c.section ? h('span', { class: 'section' }, c.section + ' › ') : null, c.name, c.required ? h('span', { class: 'muted' }, ' (required)') : null),
			detail ? h('div', { class: 'detail' }, String(detail)) : null));
}

function screenshot(s, runId, testName) {
	const src = shotUrl(runId, s.path);
	const caption = testName + ' › ' + (s.section ? s.section + ' › ' : '') + s.name;
	return h('button', { class: 'shot', onclick: () => openLightbox(src, caption), title: caption },
		h('img', { src, alt: caption, loading: 'lazy' }),
		h('span', {}, (s.section ? s.section + ' › ' : '') + s.name));
}

/** A file in a kept run, from the app's shot: protocol (http://shot.localhost on Windows, where custom schemes look so). */
function shotUrl(runId, path) {
	const rel = [runId, ...String(path).split('/')].map(encodeURIComponent).join('/');
	return (navigator.userAgent.includes('Windows') ? 'http://shot.localhost/' : 'shot://localhost/') + rel;
}

function openLightbox(src, caption) {
	document.getElementById('lightbox-image').src = src;
	document.getElementById('lightbox-caption').textContent = caption;
	document.getElementById('lightbox').hidden = false;
}

// ---- Keeping up with the daemon.

async function refreshProjects() {
	try {
		const reply = await ask({ type: 'projects' });
		const before = JSON.stringify(state.projects.map(p => [p.name, p.lastRun && p.lastRun.id]));
		state.projects = reply.projects;
		const after = JSON.stringify(state.projects.map(p => [p.name, p.lastRun && p.lastRun.id]));
		renderSidebar();
		// A new run in the project on show: list it.
		if (before !== after && state.view.name === 'project' && state.loaded) loadProject(state.view);
		else if (state.view.name === 'project') render();
	} catch (e) {
		// Offline: the banner says so, and the next status brings the projects back.
	}
}

function onStatus(status) {
	const wasOffline = state.offline !== null;
	state.status = status;
	state.offline = null;
	const newest = status.recent && status.recent[0] ? status.recent[0].id + ':' + status.recent[0].endedAt : null;
	if (wasOffline || newest !== state.lastGame) {
		state.lastGame = newest;
		refreshProjects();
	}
	if (state.view.name === 'live') render();
	else {
		renderSidebar();
		renderOffline();
	}
}

document.getElementById('nav-live').addEventListener('click', () => show({ name: 'live' }));
document.getElementById('lightbox').addEventListener('click', () => { document.getElementById('lightbox').hidden = true; });
document.addEventListener('keydown', e => {
	if (e.key === 'Escape') document.getElementById('lightbox').hidden = true;
});

tauri.event.listen('status', e => onStatus(e.payload));
tauri.event.listen('offline', e => {
	state.offline = e.payload;
	renderOffline();
});
ask({ type: 'status' }).then(onStatus, () => {});
setInterval(refreshProjects, 30_000);
render();
