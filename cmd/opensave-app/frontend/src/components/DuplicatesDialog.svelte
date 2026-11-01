<script>
  // The duplicate-tracking check (lib/duplicates.js): every game tracked more
  // than once, or tracked inside another tracked folder, with what each copy
  // is and whether it can go. Only copies that are certainly redundant are
  // ticked; stopping tracking runs as a batch (lib/bulk.js), which asks first
  // and deletes no files or snapshots.
  import { createEventDispatcher, onMount } from 'svelte';
  import { t } from '../lib/i18n.js';
  import { api } from '../lib/api.js';
  import { duplicateGroups, judgeGroup } from '../lib/duplicates.js';
  import { startBulk } from '../lib/bulk.js';
  import { timeAgo } from '../lib/timeago.js';
  import Modal from './ui/Modal.svelte';
  import ModalFoot from './ui/ModalFoot.svelte';
  import ModalLoading from './ui/ModalLoading.svelte';
  import CopyCheck from 'lucide-svelte/icons/copy-check';

  /** Every tracked game. */
  export let games = [];

  const dispatch = createEventDispatcher();
  const close = () => dispatch('close');

  let loading = true;
  let failed = '';
  let groups = [];
  let picked = new Set();

  onMount(async () => {
    const found = duplicateGroups(games);
    const ids = [...new Set(found.flat().map((g) => g.id))];
    try {
      const listings = await Promise.all(ids.map((id) => api.get(`/api/games/${id}/save-files`).catch(() => ({ files: [] }))));
      const files = Object.fromEntries(ids.map((id, i) => [id, listings[i]?.files ?? []]));
      groups = found.map((members) => judgeGroup(members, files, games));
      picked = new Set(groups.flat().filter((r) => r.verdict === 'redundant').map((r) => r.game.id));
    } catch (e) {
      failed = e.message;
    }
    loading = false;
  });

  const toggle = (id) => {
    if (picked.has(id)) picked.delete(id);
    else picked.add(id);
    picked = picked;
  };

  $: counts = {
    redundant: groups.flat().filter((r) => r.verdict === 'redundant').length,
    review: groups.flat().filter((r) => r.verdict === 'review').length
  };

  function reasonText(r, $t) {
    switch (r.code) {
      case 'covers':
        return $t('duplicates.reason.covers', { names: [...new Set(r.others.map((o) => o.name))].join('、') });
      case 'insideOf':
        return $t('duplicates.reason.insideOf', { name: r.other.name });
      case 'mixed':
        return $t('duplicates.reason.mixed', { ids: r.appIds.join(', ') });
      case 'uncovered':
        return r.appIds.length ? $t('duplicates.reason.uncoveredApps', { n: r.count, ids: r.appIds.join(', ') }) : $t('duplicates.reason.uncovered', { n: r.count });
      case 'sameFiles':
        return $t('duplicates.reason.sameFiles', { name: r.other.name, path: r.other.savePath });
      case 'newest':
        return $t('duplicates.reason.newest');
      case 'different':
        return $t('duplicates.reason.different');
    }
    return '';
  }

  function stopPicked() {
    const list = groups.flat().filter((r) => picked.has(r.game.id)).map((r) => r.game);
    close();
    startBulk('untrack', list);
  }
</script>

<Modal title={$t('duplicates.title')} icon={CopyCheck} onClose={close} width={860} height="min(84vh, 820px)">
  <svelte:fragment slot="sub">
    {#if loading}{$t('duplicates.checking')}{:else if groups.length}{$t('duplicates.summary', { groups: groups.length, redundant: counts.redundant, review: counts.review })}{:else}{$t('duplicates.none')}{/if}
  </svelte:fragment>

  {#if loading}
    <ModalLoading />
  {:else}
    <div class="body">
      <p class="intro">{$t('duplicates.intro')}</p>
      {#if failed}<p class="error">{failed}</p>{/if}
      {#each groups as group, gi}
        <section class="group">
          {#each group as r (r.game.id)}
            <label class="member {r.verdict}">
              <input type="checkbox" checked={picked.has(r.game.id)} on:change={() => toggle(r.game.id)} />
              <div class="main">
                <div class="head">
                  <strong>{r.game.name}</strong>
                  <span class="badge {r.verdict}">{$t(`duplicates.verdict.${r.verdict}`)}</span>
                  <span class="stats">{$t('duplicates.stats', { n: r.fileCount, when: r.latestMs ? timeAgo(new Date(r.latestMs).toISOString()) : $t('duplicates.never') })}</span>
                </div>
                <code class="path">{r.game.savePath}</code>
                <ul class="reasons">
                  {#each r.reasons as reason}<li>{reasonText(reason, $t)}</li>{/each}
                </ul>
              </div>
            </label>
          {/each}
        </section>
      {/each}
    </div>
  {/if}

  <ModalFoot>
    <span class="note">{$t('duplicates.footNote')}</span>
    <div class="actions">
      <button class="btn" on:click={close}>{$t('duplicates.close')}</button>
      <button class="btn danger" disabled={loading || picked.size === 0} on:click={stopPicked}>{$t('duplicates.stopTracking', { n: picked.size })}</button>
    </div>
  </ModalFoot>
</Modal>

<style>
  .body {
    padding: 14px 22px;
    overflow-y: auto;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .intro,
  .note {
    font-size: 0.82rem;
    color: var(--text-faint);
    line-height: 1.55;
  }
  .error {
    color: var(--danger, #ef4444);
    font-size: 0.85rem;
  }
  .actions {
    display: flex;
    gap: 8px;
  }
  .group {
    border: 1px solid var(--border);
    border-radius: 10px;
    overflow: hidden;
  }
  .member {
    display: flex;
    gap: 10px;
    padding: 10px 14px;
    cursor: pointer;
    align-items: flex-start;
  }
  .member + .member {
    border-top: 1px solid var(--border);
  }
  .member input {
    margin-top: 3px;
  }
  .main {
    min-width: 0;
    flex: 1;
  }
  .head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }
  .stats {
    font-size: 0.78rem;
    color: var(--text-faint);
    margin-left: auto;
  }
  .badge {
    font-size: 0.72rem;
    padding: 1px 8px;
    border-radius: 999px;
    border: 1px solid var(--border-strong);
    color: var(--text-dim);
  }
  .badge.redundant {
    border-color: var(--danger, #ef4444);
    color: var(--danger, #ef4444);
  }
  .badge.review {
    border-color: var(--warn, #f59e0b);
    color: var(--warn, #f59e0b);
  }
  .badge.keep {
    border-color: var(--success, #22c55e);
    color: var(--success, #22c55e);
  }
  .path {
    display: block;
    margin-top: 4px;
    font-size: 0.76rem;
    color: var(--text-dim);
    overflow-wrap: anywhere;
  }
  .reasons {
    margin: 5px 0 0;
    padding-left: 18px;
    font-size: 0.8rem;
    color: var(--text-dim);
    line-height: 1.5;
  }
</style>
