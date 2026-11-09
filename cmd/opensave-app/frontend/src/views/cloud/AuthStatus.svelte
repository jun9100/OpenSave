<script>
  // The connection state of the saved game-save configuration, plus the
  // one-time shared-token warning. It lives in the settings card rather than
  // in a toast: the warning is about a setting the user is looking at, and a
  // toast would be gone before they could act on it.
  import { onMount } from 'svelte';
  import CircleCheck from 'lucide-svelte/icons/circle-check';
  import CircleX from 'lucide-svelte/icons/circle-x';
  import Info from 'lucide-svelte/icons/info';
  import KeyRound from 'lucide-svelte/icons/key-round';
  import TriangleAlert from 'lucide-svelte/icons/triangle-alert';
  import Spinner from '../../components/ui/Spinner.svelte';
  import { t, trError } from '../../lib/i18n.js';
  import { authStatusView, dismissAuthWarning, getAuthStatus } from '../../lib/pairing.js';

  export let refreshKey = 0;
  export let onPair = () => {};

  const ICONS = { ok: CircleCheck, info: Info, warn: TriangleAlert, error: CircleX };

  let view = null;
  let warning = false;
  let loading = false;
  let dismissing = false;
  let warningError = '';
  let mounted = false;
  let loadedKey;
  let requestSeq = 0;

  onMount(() => {
    mounted = true;
    load(refreshKey);
  });

  $: if (mounted && refreshKey !== loadedKey) load(refreshKey);

  async function load(key) {
    loadedKey = key;
    const current = ++requestSeq;
    loading = true;
    try {
      const status = await getAuthStatus();
      // A newer refresh may have started while this one was in flight.
      if (current !== requestSeq) return;
      view = authStatusView(status);
      warning = !!status?.warning;
      warningError = '';
    } catch (e) {
      if (current !== requestSeq) return;
      view = {
        tone: 'warn',
        key: 'cloud.auth.error',
        params: { error: trError(e.message) },
        showPair: false
      };
      warning = false;
    } finally {
      if (current === requestSeq) loading = false;
    }
  }

  async function dismissWarning() {
    if (dismissing) return;
    dismissing = true;
    warningError = '';
    try {
      await dismissAuthWarning();
      warning = false;
    } catch (e) {
      warningError = trError(e.message);
    } finally {
      dismissing = false;
    }
  }
</script>

{#if loading && !view}
  <div class="auth checking" aria-live="polite"><Spinner size={13} />{$t('cloud.auth.checking')}</div>
{:else if view && view.tone !== 'none'}
  <div class="auth tone-{view.tone}" aria-live="polite">
    <svelte:component this={ICONS[view.tone]} size={16} />
    <span class="text">{$t(view.key, view.params)}</span>
    {#if view.showPair}
      <button class="btn small" type="button" on:click={onPair}><KeyRound size={14} />{$t('cloud.pair.open')}</button>
    {/if}
  </div>
{/if}

{#if warning}
  <div class="warning" role="status">
    <TriangleAlert size={16} />
    <span>{$t('cloud.auth.warning')}</span>
    {#if warningError}<span class="warning-error">{$t('cloud.auth.dismissFailed', { error: warningError })}</span>{/if}
    <button class="btn small" type="button" disabled={dismissing} on:click={dismissWarning}>
      {#if dismissing}<Spinner size={12} />{/if}{$t('cloud.auth.dismiss')}
    </button>
  </div>
{/if}

<style>
  .auth {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 28px;
    margin: 2px 0 14px;
    font-size: 0.84rem;
  }
  .auth :global(svg) {
    flex-shrink: 0;
  }
  .auth .text {
    flex: 1;
    min-width: 0;
    line-height: 1.45;
  }
  .auth.checking {
    color: var(--text-faint);
  }
  .auth.tone-ok {
    color: var(--success);
  }
  .auth.tone-info {
    color: var(--accent-text);
  }
  .auth.tone-warn {
    color: var(--warn);
  }
  .auth.tone-error {
    color: var(--danger-text);
  }
  .warning {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 9px 12px;
    margin: 0 0 14px;
    border-left: 3px solid var(--warn);
    background: rgba(var(--warn-rgb), 0.08);
    color: var(--text-dim);
    font-size: 0.8rem;
    line-height: 1.45;
  }
  .warning :global(svg) {
    flex-shrink: 0;
    color: var(--warn);
  }
  .warning .btn {
    margin-left: auto;
  }
  .warning-error {
    color: var(--warn);
    font-size: 0.76rem;
  }
</style>
