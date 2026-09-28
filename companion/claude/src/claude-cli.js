import os from 'node:os';
import path from 'node:path';
import { mkdirSync } from 'node:fs';
import pty from 'node-pty';
import xterm from '@xterm/headless';
import { ensureNodePtyHelperExecutable } from './node-pty-runtime.js';
import { isClaudeUsageLoading, parseClaudeUsageOutput } from './quota-parser.js';

const TERMINAL_COLUMNS = 120;
const TERMINAL_ROWS = 60;

export class ClaudeUsageSession {
  constructor({
    command = 'claude',
    homeDirectory = os.homedir(),
    startupDelayMillis = 6_000,
    commandDelayMillis = 350,
    timeoutMillis = 45_000,
    parseSettleMillis = 2_500,
    minimumObservationMillis = 3_000,
    spawn = pty.spawn
  } = {}) {
    this.command = command;
    this.workingDirectory = path.join(homeDirectory, '.codexbar', 'claude-workspace');
    this.startupDelayMillis = startupDelayMillis;
    this.commandDelayMillis = commandDelayMillis;
    this.timeoutMillis = timeoutMillis;
    this.parseSettleMillis = parseSettleMillis;
    this.minimumObservationMillis = minimumObservationMillis;
    this.spawn = spawn;
    this.terminal = null;
    this.screen = null;
    this.pending = null;
  }

  collect({ now = new Date() } = {}) {
    if (this.pending != null) {
      return Promise.reject(new Error('A Claude usage refresh is already running'));
    }
    const startedNow = this.ensureTerminal();
    return new Promise((resolve, reject) => {
      const pending = {
        resolve,
        reject,
        now,
        capture: '',
        pendingWrites: 0,
        waitingForScreen: false,
        active: false,
        requestSentAt: 0,
        candidateFingerprint: null,
        sendTimer: null,
        settleTimer: null,
        timeoutTimer: null
      };
      this.pending = pending;

      if (!startedNow) this.terminal.write('\u001b');
      pending.sendTimer = setTimeout(() => {
        if (this.pending !== pending || this.terminal == null) return;
        pending.active = true;
        pending.capture = '';
        pending.requestSentAt = Date.now();
        this.terminal.write('/usage\r');
      }, startedNow ? this.startupDelayMillis : this.commandDelayMillis);
      pending.timeoutTimer = setTimeout(() => {
        this.finishPending(
          new Error(
            'Claude Code did not return complete plan usage. Run `claude` in ~/.codexbar/claude-workspace, finish sign-in and trust prompts, then confirm `/usage` works.'
          )
        );
        // A login prompt or stuck redraw must not poison every later refresh.
        this.close();
      }, this.timeoutMillis);
    });
  }

  close() {
    if (this.pending != null) {
      this.finishPending(new Error('Claude usage session closed'));
    }
    const terminal = this.terminal;
    const screen = this.screen;
    this.terminal = null;
    this.screen = null;
    screen?.dispose();
    try {
      terminal?.kill();
    } catch {
      // Process already exited.
    }
  }

  ensureTerminal() {
    if (this.terminal != null) return false;
    mkdirSync(this.workingDirectory, { recursive: true, mode: 0o700 });
    ensureNodePtyHelperExecutable();
    const screen = new xterm.Terminal({
      cols: TERMINAL_COLUMNS,
      rows: TERMINAL_ROWS,
      scrollback: 0,
      allowProposedApi: true
    });
    let terminal;
    try {
      terminal = this.spawn(this.command, ['--allowed-tools', ''], {
        name: 'xterm-color',
        cols: TERMINAL_COLUMNS,
        rows: TERMINAL_ROWS,
        cwd: this.workingDirectory,
        env: {
          ...process.env,
          NO_COLOR: '1'
        }
      });
    } catch (error) {
      screen.dispose();
      throw error;
    }
    this.terminal = terminal;
    this.screen = screen;
    terminal.onData((data) => {
      if (this.terminal === terminal) this.handleData(data);
    });
    terminal.onExit(({ exitCode }) => {
      if (this.terminal !== terminal) return;
      this.terminal = null;
      this.screen = null;
      screen.dispose();
      if (this.pending != null) {
        this.finishPending(
          new Error(`Claude Code exited before plan usage was available (${exitCode})`)
        );
      }
    });
    return true;
  }

  handleData(data) {
    const screen = this.screen;
    if (screen == null) return;
    const pending = this.pending;
    const active = pending?.active === true;
    if (active) pending.pendingWrites += 1;
    // Keep one bounded screen for the PTY: ConPTY sends changes to existing cells,
    // not complete lines. Ignoring cursor movement loses unchanged weekly labels.
    screen.write(data, () => {
      if (this.screen !== screen || !active || this.pending !== pending) return;
      pending.pendingWrites -= 1;
      const buffer = screen.buffer.active;
      pending.capture = Array.from({ length: screen.rows }, (_, row) =>
        buffer.getLine(buffer.baseY + row)?.translateToString(true) ?? ''
      ).join('\n');
      this.handleScreen(pending);
      if (pending.waitingForScreen && pending.pendingWrites === 0) {
        this.finishStableCandidate(pending);
      }
    });
  }

  handleScreen(pending) {
    if (isClaudeUsageLoading(pending.capture)) {
      clearTimeout(pending.settleTimer);
      pending.settleTimer = null;
      pending.waitingForScreen = false;
      pending.candidateFingerprint = null;
      return;
    }
    const parsed = parseClaudeUsageOutput(pending.capture, pending.now);
    if (parsed == null) {
      clearTimeout(pending.settleTimer);
      pending.settleTimer = null;
      pending.waitingForScreen = false;
      pending.candidateFingerprint = null;
      return;
    }
    const fingerprint = JSON.stringify(parsed);
    if (fingerprint === pending.candidateFingerprint && pending.settleTimer != null) return;
    clearTimeout(pending.settleTimer);
    pending.waitingForScreen = false;
    pending.candidateFingerprint = fingerprint;
    pending.settleTimer = setTimeout(
      () => this.finishStableCandidate(pending),
      this.parseSettleMillis
    );
  }

  finishStableCandidate(pending) {
    if (this.pending !== pending || pending.candidateFingerprint == null) return;
    if (pending.pendingWrites > 0) {
      pending.waitingForScreen = true;
      return;
    }
    pending.waitingForScreen = false;
    const elapsed = Date.now() - pending.requestSentAt;
    if (elapsed < this.minimumObservationMillis) {
      pending.settleTimer = setTimeout(
        () => this.finishStableCandidate(pending),
        this.minimumObservationMillis - elapsed
      );
      return;
    }
    if (isClaudeUsageLoading(pending.capture)) return;
    const parsed = parseClaudeUsageOutput(pending.capture, pending.now);
    if (parsed == null || JSON.stringify(parsed) !== pending.candidateFingerprint) return;
    this.finishPending(null, parsed);
  }

  finishPending(error, value) {
    const pending = this.pending;
    if (pending == null) return;
    this.pending = null;
    clearTimeout(pending.sendTimer);
    clearTimeout(pending.settleTimer);
    clearTimeout(pending.timeoutTimer);
    try {
      this.terminal?.write('\u001b');
    } catch {
      // Process exited while the result was being finalized.
    }
    if (error) pending.reject(error);
    else pending.resolve(value);
  }
}
