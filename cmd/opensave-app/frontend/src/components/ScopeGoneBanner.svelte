<script context="module">
  // Put off for this run. Kept outside the instance because the page is
  // redrawn when the language changes, and the question should not come back
  // just because it was answered in another language.
  const putOff = new Set();
</script>

<script>
  // The cloud scope behind a tracked game was deleted or merged into another
  // one on the server (C6). Sync for it has stopped; this asks what to do
  // with the local entry. It stays until the user picks something — "later"
  // only hides it for this run, because the record on the daemon is still
  // pending and the question should come back next launch.
  import { onMount } from 'svelte';
  import { fly as flyIn } from 'svelte/transition';
  import { gated } from '../lib/motion.js';
  // Instant with animations off; see lib/motion.js.
  const fly = gated(flyIn);
  import { askConfirm, cloudOffers, games, toast } from '../lib/stores.js';
  import { t, trError } from '../lib/i18n.js';
  import { goneNotice, listScopeGone, pendingGone, stopSyncingGone } from '../lib/scopegone.js';
  import { untrackGame } from '../lib/gameactions.js';
  import CloudOff from 'lucide-svelte/icons/cloud-off';
  import GitMerge from 'lucide-svelte/icons/git-merge';

  let items = [];
  // Put off for this run only: the daemon's record stays pending, so the
  // banner is back next launch.
  let hidden = new Set(putOff);
  let busy = '';

  $: pending = pendingGone(items, hidden);

  onMount(() => {
    load();
    // The daemon sends cloud-offers whenever it re-reads the cloud, which is
    // also when a new tombstone can appear; no polling of our own.
    let first = true;
    const unsubscribe = cloudOffers.subscribe(() => {
      if (first) {
        first = false;
        return;
      }
      load();
    });
    // Coming back to the window is a good moment to catch up on what changed
    // while it was in the background.
    window.addEventListener('focus', load);
    return () => {
      window.removeEventListener('focus', load);
      unsubscribe();
    };
  });

  async function load() {
    try {
      const result = await listScopeGone();
      items = result?.items ?? [];
    } catch {
      // The Cloud tab says the same thing; a banner that cannot refresh keeps
      // what it showed rather than interrupting the page.
    }
  }

  function nameOf(item) {
    return $games[item.gameId]?.name || item.gameId;
  }

  function gameFor(item) {
    return $games[item.gameId] ?? { id: item.gameId, name: item.gameId };
  }

  function later(item) {
    putOff.add(item.gameId);
    hidden = new Set(putOff);
  }

  async function stopSyncing(item) {
    if (busy) return;
    busy = item.gameId;
    try {
      await stopSyncingGone(item.gameId);
      await load();
    } catch (e) {
      toast(trError(e.message), 'error');
    } finally {
      busy = '';
    }
  }

  async function untrack(item) {
    if (busy) return;
    const game = gameFor(item);
    if (
      !(await askConfirm($t('cloud.gone.untrackConfirmMessage'), {
        title: $t('cloud.gone.untrackConfirmTitle'),
        confirmText: $t('cloud.gone.untrack'),
        danger: true
      }))
    )
      return;
    busy = item.gameId;
    try {
      // The merged scope's cloud copies are not this device's to delete.
      await untrackGame(game, { skipCloudCleanup: true });
      await load();
    } catch (e) {
      toast(trError(e.message), 'error');
    } finally {
      busy = '';
    }
  }
</script>

{#if pending.length > 0}
  <div class="gone-wrap" transition:fly={{ y: -90, duration: 320 }}>
    {#each pending as item (item.gameId)}
      {@const notice = goneNotice(item)}
      <div class="gone-card" transition:fly={{ y: -20, duration: 200 }}>
        <div class="gone-icon">
          {#if notice.key === 'cloud.gone.retired'}
            <GitMerge size={19} />
          {:else}
            <CloudOff size={19} />
          {/if}
        </div>
        <div class="gone-body">
          <div class="gone-title">{nameOf(item)}</div>
          <div class="gone-text">{$t(notice.key, notice.params)}</div>
          {#if notice.hintKey}
            <div class="gone-hint">{$t(notice.hintKey, notice.params)}</div>
          {/if}
          {#if notice.refusedKey}
            <div class="gone-hint">{$t(notice.refusedKey)}</div>
          {/if}
        </div>
        <div class="gone-actions">
          {#if notice.actions.includes('off')}
            <button class="btn small primary" disabled={!!busy} on:click={() => stopSyncing(item)}>
              {$t('cloud.gone.off')}
            </button>
          {/if}
          {#if notice.actions.includes('untrack')}
            <button class="btn small primary" disabled={!!busy} on:click={() => untrack(item)}>
              {$t('cloud.gone.untrack')}
            </button>
          {/if}
          <button class="btn small" disabled={!!busy} on:click={() => later(item)}>{$t('cloud.gone.later')}</button>
        </div>
      </div>
    {/each}
  </div>
{/if}

<style>
  /* Placed by App.svelte beside its notice stack. */
  .gone-wrap {
    display: flex;
    flex-direction: column;
    gap: 8px;
    max-width: 760px;
    margin-bottom: 18px;
  }
  .gone-card {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 13px 16px;
    background: var(--bg-raised);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: 0 12px 40px rgba(0, 0, 0, 0.55);
  }
  .gone-icon {
    flex-shrink: 0;
    width: 36px;
    height: 36px;
    border-radius: 10px;
    display: grid;
    place-items: center;
    background: var(--bg-hover);
    color: var(--text-dim);
  }
  .gone-body {
    flex: 1;
    min-width: 0;
  }
  .gone-title {
    font-size: 0.92rem;
    font-weight: 650;
  }
  .gone-text {
    font-size: 0.82rem;
    color: var(--text-dim);
    margin-top: 2px;
  }
  .gone-hint {
    font-size: 0.78rem;
    color: var(--text-faint);
    margin-top: 4px;
  }
  .gone-actions {
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex-shrink: 0;
  }
</style>
