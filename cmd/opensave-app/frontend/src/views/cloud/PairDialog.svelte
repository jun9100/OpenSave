<script>
  // Three steps, and the middle one is not optional: a pairing code can point
  // at any server, so the host has to be shown and confirmed before a token
  // is claimed. Everything else here is just getting the preview and claim
  // requests into the shape the local daemon expects.
  import CircleCheck from 'lucide-svelte/icons/circle-check';
  import KeyRound from 'lucide-svelte/icons/key-round';
  import ShieldAlert from 'lucide-svelte/icons/shield-alert';
  import Modal from '../../components/ui/Modal.svelte';
  import Spinner from '../../components/ui/Spinner.svelte';
  import { t, trError } from '../../lib/i18n.js';
  import { claimPairing, isPairLink, loginAccount, previewPairing } from '../../lib/pairing.js';

  export let initialServer = '';
  export let onClose = () => {};
  export let onPaired = () => {};

  let step = 'input';
  let mode = 'code';
  let server = initialServer ?? '';
  let code = '';
  let link = '';
  let username = '';
  let password = '';
  let preview = null;
  let name = '';
  let busy = false;
  let error = '';
  let pairedNotified = false;

  // A whole link in the address field is valid too, so it can stand in for
  // the code: that is how a link copied from a QR code gets pasted.
  $: canPreview = mode === 'link'
    ? !!link.trim()
    : mode === 'account'
      ? !!server.trim() && !!username.trim() && !!password
      : !!server.trim() && (!!code.trim() || isPairLink(server));

  /** The account flow has no code to preview, so the host is derived here and
   *  still shown on the confirmation step before anything is sent. */
  function accountHost(address) {
    const raw = String(address || '').trim();
    if (!raw) return '';
    try {
      return new URL(/^[a-z]+:\/\//i.test(raw) ? raw : `http://${raw}`).host;
    } catch {
      return '';
    }
  }

  function switchMode(next) {
    if (busy || mode === next) return;
    mode = next;
    error = '';
  }

  async function previewPair() {
    if (!canPreview || busy) return;
    busy = true;
    error = '';
    try {
      if (mode === 'account') {
        const host = accountHost(server);
        if (!host) throw new Error($t('cloud.pair.serverHint'));
        preview = { server: server.trim(), host, username: username.trim() };
      } else {
        preview = await previewPairing({ server, code, link: mode === 'link' ? link : '' });
      }
      step = 'confirm';
    } catch (e) {
      error = e.message;
    } finally {
      busy = false;
    }
  }

  async function claim() {
    if (!preview || busy) return;
    busy = true;
    error = '';
    try {
      const res = mode === 'account'
        ? await loginAccount({
            server: preview.server,
            username: preview.username,
            password,
            confirmedHost: preview.host
          })
        : await claimPairing({
            server: preview.server,
            code: preview.code,
            confirmedHost: preview.host
          });
      name = String(res?.name ?? '').trim() || String(res?.username ?? '').trim() ||
        String(res?.tokenId ?? '') || preview.host;
      step = 'done';
    } catch (e) {
      // Stay on the confirmation step: the service may be too old, or the
      // code may have expired while the user was reading the host.
      error = e.message;
    } finally {
      busy = false;
    }
  }

  function back() {
    if (busy) return;
    step = 'input';
    error = '';
  }

  function notifyPaired() {
    if (pairedNotified) return;
    pairedNotified = true;
    onPaired();
  }

  function finish() {
    notifyPaired();
    onClose();
  }

  function close() {
    if (busy) return;
    // A successful claim must refresh the settings even if the dialog is
    // dismissed with Escape or the close button rather than "Done".
    if (step === 'done') finish();
    else onClose();
  }
</script>

<Modal
  title={$t('cloud.pair.title')}
  icon={KeyRound}
  onClose={close}
  closable={!busy}
  width={560}
  height="auto"
  maxHeight="min(85vh, 720px)"
>
  <div class="body">
    {#if step === 'input'}
      <p class="lead">{$t('cloud.pair.intro')}</p>

      <div class="modes" role="group" aria-label={$t('cloud.pair.method')}>
        <button type="button" class:on={mode === 'code'} aria-pressed={mode === 'code'} disabled={busy} on:click={() => switchMode('code')}>
          {$t('cloud.pair.modeCode')}
        </button>
        <button type="button" class:on={mode === 'link'} aria-pressed={mode === 'link'} disabled={busy} on:click={() => switchMode('link')}>
          {$t('cloud.pair.modeLink')}
        </button>
        <button type="button" class:on={mode === 'account'} aria-pressed={mode === 'account'} disabled={busy} on:click={() => switchMode('account')}>
          {$t('cloud.pair.modeAccount')}
        </button>
      </div>

      {#if mode === 'code'}
        <div class="field">
          <label for="pair-server">{$t('cloud.pair.server')}</label>
          <input id="pair-server" bind:value={server} placeholder="https://nas.example:8788" autocomplete="off" spellcheck="false" />
          <span class="hint">{$t('cloud.pair.serverHint')}</span>
        </div>
        <div class="field">
          <label for="pair-code">{$t('cloud.pair.code')}</label>
          <input id="pair-code" bind:value={code} placeholder="ABCD-EFGH" autocomplete="off" spellcheck="false" />
          <span class="hint">{$t('cloud.pair.codeHint')}</span>
        </div>
      {:else if mode === 'account'}
        <div class="field">
          <label for="pair-account-server">{$t('cloud.pair.server')}</label>
          <input id="pair-account-server" bind:value={server} placeholder="https://nas.example:8788" autocomplete="off" spellcheck="false" />
          <span class="hint">{$t('cloud.pair.serverHint')}</span>
        </div>
        <div class="field">
          <label for="pair-username">{$t('cloud.pair.username')}</label>
          <input id="pair-username" bind:value={username} autocomplete="username" spellcheck="false" />
          <span class="hint">{$t('cloud.pair.usernameHint')}</span>
        </div>
        <div class="field">
          <label for="pair-password">{$t('cloud.pair.password')}</label>
          <input id="pair-password" type="password" bind:value={password} autocomplete="current-password" />
          <span class="hint">{$t('cloud.pair.passwordHint')}</span>
        </div>
      {:else}
        <div class="field">
          <label for="pair-link">{$t('cloud.pair.link')}</label>
          <input id="pair-link" bind:value={link} placeholder="savevault://pair?v=1&server=…&code=…" autocomplete="off" spellcheck="false" />
          <span class="hint">{$t('cloud.pair.linkHint')}</span>
        </div>
      {/if}

      {#if error}<p class="error" role="alert">{trError(error)}</p>{/if}

      <div class="actions">
        <button class="btn" type="button" disabled={busy} on:click={close}>{$t('common.cancel')}</button>
        <button class="btn primary" type="button" disabled={busy || !canPreview} on:click={previewPair}>
          {#if busy}<Spinner size={13} />{/if}{$t('cloud.pair.next')}
        </button>
      </div>
    {:else if step === 'confirm'}
      <p class="lead">{$t('cloud.pair.confirmLead')}</p>

      <div class="host-box">
        <span class="host-label">{$t('cloud.pair.host')}</span>
        <strong class="host">{preview?.host}</strong>
        <dl class="details">
          <div>
            <dt>{$t('cloud.pair.server')}</dt>
            <dd><code>{preview?.server}</code></dd>
          </div>
          {#if mode === 'account'}
            <div>
              <dt>{$t('cloud.pair.username')}</dt>
              <dd><code>{preview?.username}</code></dd>
            </div>
          {:else}
            <div>
              <dt>{$t('cloud.pair.code')}</dt>
              <dd><code>{preview?.code}</code></dd>
            </div>
          {/if}
        </dl>
      </div>

      <p class="notice">
        <ShieldAlert size={16} />
        <span>{mode === 'account' ? $t('cloud.pair.confirmAccountHint') : $t('cloud.pair.confirmHint')}</span>
      </p>

      {#if error}<p class="error" role="alert">{trError(error)}</p>{/if}

      <div class="actions">
        <button class="btn" type="button" disabled={busy} on:click={back}>{$t('cloud.pair.back')}</button>
        <button class="btn primary" type="button" disabled={busy} on:click={claim}>
          {#if busy}<Spinner size={13} />{/if}{$t('cloud.pair.confirm')}
        </button>
      </div>
    {:else}
      <div class="done">
        <CircleCheck size={36} />
        <h3>{$t('cloud.pair.doneTitle')}</h3>
        <p class="paired">{$t('cloud.pair.paired', { name })}</p>
        <p class="hint">{$t('cloud.pair.doneHint')}</p>
      </div>

      <div class="actions">
        <button class="btn primary" type="button" on:click={finish}>{$t('cloud.pair.finish')}</button>
      </div>
    {/if}
  </div>
</Modal>

<style>
  .body {
    padding: 16px 22px 20px;
    overflow: auto;
  }
  .lead {
    font-size: 0.86rem;
    color: var(--text-dim);
    line-height: 1.5;
    margin-bottom: 14px;
  }
  .modes {
    display: flex;
    gap: 2px;
    padding: 3px;
    margin-bottom: 16px;
    border: 1px solid var(--border);
    border-radius: 10px;
    background: var(--bg);
  }
  .modes button {
    flex: 1;
    height: 30px;
    padding: 0 10px;
    border: none;
    border-radius: 7px;
    background: transparent;
    color: var(--text-dim);
    font: inherit;
    font-size: 0.84rem;
    font-weight: 500;
    cursor: pointer;
  }
  .modes button:hover {
    color: var(--text);
    background: var(--bg-hover);
  }
  .modes button.on {
    background: var(--bg-active);
    color: var(--text);
  }
  .host-box {
    padding: 14px 16px;
    margin-bottom: 14px;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg);
  }
  .host-label {
    display: block;
    font-size: 0.74rem;
    color: var(--text-faint);
    margin-bottom: 4px;
  }
  .host {
    display: block;
    font-size: 1.28rem;
    letter-spacing: -0.01em;
    word-break: break-all;
  }
  .details {
    display: grid;
    gap: 8px;
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid var(--border);
  }
  .details div {
    display: grid;
    grid-template-columns: 84px minmax(0, 1fr);
    gap: 8px;
    align-items: baseline;
  }
  .details dt {
    font-size: 0.74rem;
    color: var(--text-faint);
  }
  .details dd {
    min-width: 0;
  }
  .details code {
    display: block;
    font-size: 0.78rem;
    color: var(--text-dim);
    line-height: 1.4;
    word-break: break-all;
  }
  .notice {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    padding: 10px 12px;
    margin-bottom: 14px;
    border-left: 3px solid var(--warn);
    background: rgba(var(--warn-rgb), 0.08);
    font-size: 0.8rem;
    color: var(--text-dim);
    line-height: 1.5;
  }
  .notice :global(svg) {
    flex-shrink: 0;
    margin-top: 1px;
    color: var(--warn);
  }
  .error {
    color: var(--warn);
    font-size: 0.8rem;
    line-height: 1.45;
    margin-bottom: 10px;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 16px;
  }
  .done {
    text-align: center;
    padding: 10px 8px 4px;
  }
  .done :global(svg) {
    color: var(--success);
  }
  .done h3 {
    font-size: 1.05rem;
    margin: 10px 0 6px;
  }
  .paired {
    font-size: 0.9rem;
    color: var(--text);
    margin-bottom: 6px;
    word-break: break-word;
  }
  .done .hint {
    margin-bottom: 0;
  }
  .done + .actions {
    justify-content: center;
  }
</style>
