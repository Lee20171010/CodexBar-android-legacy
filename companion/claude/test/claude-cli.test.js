import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, rmdirSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { ClaudeUsageSession } from '../src/claude-cli.js';

test('uses a persistent dedicated workspace and reuses the Claude PTY for stable usage', async (t) => {
  const homeDirectory = mkdtempSync(path.join(os.tmpdir(), 'codexbar-claude-test-'));
  const workspaceDirectory = path.join(homeDirectory, '.codexbar', 'claude-workspace');
  t.after(() => {
    if (existsSync(workspaceDirectory)) rmdirSync(workspaceDirectory);
    if (existsSync(path.dirname(workspaceDirectory))) rmdirSync(path.dirname(workspaceDirectory));
    rmdirSync(homeDirectory);
  });
  let spawnCount = 0;
  let killed = false;
  const writes = [];
  let dataHandler = () => {};
  let exitHandler = () => {};
  const terminal = {
    onData(handler) {
      dataHandler = handler;
    },
    onExit(handler) {
      exitHandler = handler;
    },
    write(value) {
      writes.push(value);
      if (value !== '/usage\r') return;
      setTimeout(() => dataHandler('\u001b[2J\u001b[HAccount & Usage\r\nCurrent session\r\nLoading...\r\n'), 1);
      setTimeout(() => dataHandler('\u001b[HAccount & Usage\r\nCurrent\u001b['), 5);
      setTimeout(() => dataHandler(
        '1Csession\r\n25% used\u001b[K\r\nCurrent\u001b[1Cweek (all models)\r\n40% used\r\nPlan: Pro\r\n'
      ), 8);
    },
    kill() {
      killed = true;
      exitHandler({ exitCode: 0 });
    }
  };
  const session = new ClaudeUsageSession({
    spawn(command, args, options) {
      spawnCount += 1;
      assert.equal(command, 'claude-test');
      assert.deepEqual(args, ['--allowed-tools', '']);
      assert.equal(options.cwd, workspaceDirectory);
      assert.notEqual(options.cwd, homeDirectory);
      assert.equal(existsSync(workspaceDirectory), true);
      return terminal;
    },
    command: 'claude-test',
    homeDirectory,
    startupDelayMillis: 0,
    commandDelayMillis: 0,
    timeoutMillis: 500,
    parseSettleMillis: 15,
    minimumObservationMillis: 20
  });
  t.after(() => session.close());

  const first = await session.collect({ now: new Date('2026-08-23T00:00:00Z') });
  const second = await session.collect({ now: new Date('2026-08-23T00:05:00Z') });

  assert.equal(spawnCount, 1);
  assert.equal(writes.filter((value) => value === '/usage\r').length, 2);
  assert.deepEqual(first, second);
  assert.deepEqual(first, {
    tier: 'Pro',
    windows: [
      { label: '5-Hour', usedFraction: 0.25 },
      { label: '7-Day', usedFraction: 0.4 }
    ]
  });
  assert.equal(killed, false);

  session.close();
  assert.equal(killed, true);
  assert.equal(existsSync(workspaceDirectory), true);
});

test('keeps weekly metrics and latest percentages across ConPTY cell redraws', async (t) => {
  let repaintTimer;
  t.after(() => clearInterval(repaintTimer));
  const session = fakeSession(t, (emit) => {
    emit(usageFrame(10, 45));
    setTimeout(() => emit(
      '\u001b[35;4H37\u001b[40;4H62' +
      '\u001b[34;4HCurrent\u001b[1Csession' +
      '\u001b[39;4HCur\u001b[1Cent\u001b[1Cwe\u001b[1Ck'
    ), 5);
    setTimeout(() => emit('\u001b[35;4H9% used\u001b[K\u001b[40;4H7% used\u001b[K'), 20);
    // Cursor-only repainting must not indefinitely postpone a stable reading.
    repaintTimer = setInterval(() => emit('\u001b[?25l\u001b[60;3H'), 5);
  });

  assert.deepEqual(await session.collect(), {
    windows: [
      { label: '5-Hour', usedFraction: 0.09 },
      { label: '7-Day', usedFraction: 0.07 }
    ]
  });
});

test('waits for a stale marker to be erased before accepting the visible reading', async (t) => {
  let freshFrameSent = false;
  const session = fakeSession(t, (emit) => {
    emit(usageFrame(10, 45) + '\u001b[43;4HShowing last-known usage');
    setTimeout(() => {
      freshFrameSent = true;
      emit('\u001b[43;1H\u001b[2K\u001b[35;4H22\u001b[40;4H44');
    }, 60);
  });

  assert.deepEqual(await session.collect(), {
    windows: [
      { label: '5-Hour', usedFraction: 0.22 },
      { label: '7-Day', usedFraction: 0.44 }
    ]
  });
  assert.equal(freshFrameSent, true);
});

function usageFrame(sessionUsed, weeklyUsed) {
  return '\u001b[2J\u001b[H' +
    '\u001b[33;4HAccount & Usage' +
    `\u001b[34;4HCurrent session\u001b[35;4H${sessionUsed}% used` +
    `\u001b[39;4HCurrent week (all models)\u001b[40;4H${weeklyUsed}% used`;
}

test('recreates a timed-out PTY and ignores late callbacks from the old process', async (t) => {
  const children = [];
  const session = fakeSession(t, () => {});
  session.timeoutMillis = 180;
  session.spawn = () => {
    const child = { killed: false, data: () => {}, exit: () => {} };
    children.push(child);
    return {
      onData(handler) { child.data = handler; },
      onExit(handler) { child.exit = handler; },
      write(value) {
        if (value !== '/usage\r' || children.length === 1) return;
        children[0].data(usageFrame(99, 99));
        children[0].exit({ exitCode: 1 });
        child.data(usageFrame(12, 34));
      },
      kill() { child.killed = true; }
    };
  };
  await assert.rejects(session.collect(), /did not return complete plan usage/);
  assert.equal(children[0].killed, true);
  assert.equal(session.terminal, null);
  assert.deepEqual((await session.collect()).windows.map(w => w.usedFraction), [0.12, 0.34]);
  assert.equal(children.length, 2);
  session.close();
  assert.equal(children[1].killed, true);
});

function fakeSession(t, onUsage) {
  const homeDirectory = mkdtempSync(path.join(os.tmpdir(), 'codexbar-claude-test-'));
  let dataHandler = () => {};
  const session = new ClaudeUsageSession({
    homeDirectory,
    startupDelayMillis: 0,
    commandDelayMillis: 0,
    timeoutMillis: 500,
    parseSettleMillis: 20,
    minimumObservationMillis: 40,
    spawn() {
      return {
        onData(handler) { dataHandler = handler; },
        onExit() {},
        write(value) { if (value === '/usage\r') onUsage(data => dataHandler(data)); },
        kill() {}
      };
    }
  });
  t.after(() => {
    session.close();
    const workspace = path.join(homeDirectory, '.codexbar', 'claude-workspace');
    if (existsSync(workspace)) rmdirSync(workspace);
    if (existsSync(path.dirname(workspace))) rmdirSync(path.dirname(workspace));
    rmdirSync(homeDirectory);
  });
  return session;
}
