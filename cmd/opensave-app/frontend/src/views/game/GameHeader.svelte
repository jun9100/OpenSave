<script>
  // The top of a game's page: its art and name, which devices have this save,
  // the Launch and Sync buttons, and the save folder with a way to open it.
  import { onDestroy } from 'svelte';
  import { peers, navigate, toast, syncActivity, askConfirm } from '../../lib/stores.js';
  import { emptiedWhere } from '../../lib/emptied.js';
  import { whenLabel } from '../../lib/snapshots.js';
  import RotateCcw from 'lucide-svelte/icons/rotate-ccw';
  import { api, native, gameCover } from '../../lib/api.js';
  import { timeAgo } from '../../lib/timeago.js';
  import { playLength } from '../../lib/format.js';
  import { canLaunch } from '../../lib/gameactions.js';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import Play from 'lucide-svelte/icons/play';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import FolderOpen from 'lucide-svelte/icons/folder-open';
  import Pencil from 'lucide-svelte/icons/pencil';
  import Star from 'lucide-svelte/icons/star';
  import TriangleAlert from 'lucide-svelte/icons/triangle-alert';
  import { collections, isFavourite, toggleFavourite } from '../../lib/collections.js';
  import { t, tt, trError, segments } from '../../lib/i18n.js';

  export let game;
  /** The page's shared runner (see lib/runner.js). */
  export let runner;
  const { busy, run } = runner;

  $: activity = $syncActivity[game.id];

  // Is my Deck up to date with THIS save? One entry per paired device, in
  // the header, because it is the question a person opens a game to ask.
  // "never" is listed too: it is the answer that explains why a save is not
  // on the other machine. A device that was unpaired is not shown even if a
  // stamp for it survived somewhere — the list is the paired devices.
  $: syncedWith = Object.values($peers)
    .map((p) => ({ id: p.id, name: p.name, at: game?.lastSyncedWith?.[p.id] ?? '' }))
    .sort((a, b) => a.name.localeCompare(b.name));
  // "4 min ago" must not freeze at the moment the page was opened.
  let now = Date.now();
  const tick = setInterval(() => (now = Date.now()), 30_000);
  onDestroy(() => clearInterval(tick));

  const syncNow = () => run(tt('game.header.syncTriggered'), () => api.post(`/api/games/${game.id}/sync`));
  const launchGame = () => run(tt('game.header.launching'), () => api.post(`/api/games/${game.id}/launch`));

  let editPath = false;
  let pathDraft = '';

  // The answer to an emptied save: put the files back here, or let the
  // deletion go to the other devices too.
  let answering = false;
  async function answerEmptied(answer) {
    if (answer === 'delete') {
      const n = game.emptied.files;
      const ok = await askConfirm(
        tt('game.header.emptied.deleteConfirm', { game: game.name, n }),
        { title: tt('game.header.emptied.deleteTitle'), confirmText: tt('game.header.emptied.deleteConfirmText'), danger: true }
      );
      if (!ok) return;
    }
    answering = true;
    try {
      const res = await api.post(`/api/games/${game.id}/emptied`, { answer });
      if (answer === 'delete') toast(tt('game.header.emptied.deletedToast', { game: game.name }), 'success');
      else if (res.fetching > 0) toast(tt('game.header.emptied.fetchingToast', { game: game.name, count: res.fetching }), 'success');
      else toast(tt('game.header.emptied.backToast', { game: game.name }), 'success');
    } catch (e) {
      toast(trError(e.message), 'error');
    } finally {
      answering = false;
    }
  }
  // Reveal the save location in Explorer / Finder / the Linux file manager.
  // The bridge returns a message when it can't (e.g. the folder was deleted).
  async function openSaveFolder() {
    const problem = await native.openFolder(game.savePath);
    if (problem) toast(trError(problem), 'error');
  }

  async function savePath() {
    await run(tt('game.header.savePathUpdated'), () => api.patch(`/api/games/${game.id}`, { savePath: pathDraft }));
    editPath = false;
  }
</script>

<div class="head">
  <button class="btn icon back" on:click={() => navigate('home')} title={$t('game.header.back')} aria-label={$t('game.header.back')}><ArrowLeft size={18} /></button>
  {#if gameCover(game)}
    <img
      class="head-cover"
      src={gameCover(game)}
      alt=""
      on:load={(e) => (e.currentTarget.style.display = '')}
      on:error={(e) => (e.currentTarget.style.display = 'none')}
    />
  {/if}
  <div class="title-block">
    <h2 class="page-title title-row">
      {game.name}
      <button
        class="btn small ghost icon star"
        class:on={isFavourite($collections, game.id)}
        title={isFavourite($collections, game.id) ? $t('game.header.removeFavourite') : $t('game.header.addFavourite')}
        aria-label={isFavourite($collections, game.id) ? $t('game.header.removeFavourite') : $t('game.header.addFavourite')}
        aria-pressed={isFavourite($collections, game.id)}
        on:click={() => toggleFavourite($collections, game)}
      >
        <Star size={16} />
      </button>
    </h2>
    <div class="sub">
      {#each segments($t('game.header.branch'), { branch: game.activeBranch }, ['branch']) as part}{#if part.bold}<strong
          >{part.text}</strong
        >{:else}{part.text}{/if}{/each}
      {#if activity?.state === 'running'}
        · <span class="syncing">{$t('game.header.syncing', { percent: activity.percentage ?? 0 })}</span>
      {/if}
      {#if game.playingSince}
        · <span class="playing"
          >{$t('game.header.inSessionSince', {
            time: new Date(game.playingSince).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
          })}</span
        >
      {:else if game.lastPlayedAt}
        · <span title={new Date(game.lastPlayedAt).toLocaleString()}
          >{$t('game.header.played', { when: timeAgo(game.lastPlayedAt, now), length: playLength(game.playtimeMs) })}</span
        >
      {/if}
      {#if game.installed === 'not-found'}
        · <span title={$t('game.header.notFoundHint')}>{$t('game.header.notFoundHere')}</span>
      {/if}
    </div>
    {#if syncedWith.length > 0}
      <div class="sub synced-with">
        {#each syncedWith as d, i (d.id)}
          {#if i > 0}<span class="sep">·</span>{/if}
          <span class="device" title={d.at
              ? $t('game.header.lastSyncedTitle', { device: d.name, when: new Date(d.at).toLocaleString() })
              : $t('game.header.neverSyncedTitle', { device: d.name })}>
            {d.name}
            <span class:never={!d.at}>{d.at ? $t('game.header.syncedAgo', { when: timeAgo(d.at, now) }) : $t('game.header.neverSynced')}</span>
          </span>
        {/each}
      </div>
    {/if}
  </div>
  <div class="head-actions">
    {#if canLaunch(game)}
      <button class="btn" disabled={$busy} on:click={launchGame}><Play size={15} />{$t('game.header.launch')}</button>
    {/if}
    <button class="btn primary" disabled={$busy} on:click={syncNow}><RefreshCw size={15} />{$t('game.header.syncNow')}</button>
  </div>
</div>

<div class="path-line">
  {#if editPath}
    <input class="path-input" bind:value={pathDraft} />
    <button class="btn small" on:click={async () => (pathDraft = (await native.selectDirectory(tt('game.header.selectSaveFolder'))) || pathDraft)}
      >{$t('game.header.browse')}</button
    >
    <button class="btn small primary" on:click={savePath}>{$t('game.header.save')}</button>
    <button class="btn small" on:click={() => (editPath = false)}>{$t('common.cancel')}</button>
  {:else}
    <span class="path" title={game.savePath}>{game.savePath}</span>
    <button class="btn small" on:click={openSaveFolder} title={$t('game.header.openFolderTitle')}>
      <FolderOpen size={14} />{$t('game.header.openFolder')}
    </button>
    <button class="btn small" on:click={() => { pathDraft = game.savePath; editPath = true; }}><Pencil size={13} />{$t('game.header.edit')}</button>
  {/if}
</div>

{#if game.emptied && !game.savePathMissing}
  <!-- Every save file went at once here. Nothing is synced until this is
       answered: syncing it would delete them on the other devices too
       (internal/p2p/syncengine/hold.go). -->
  <div class="missing emptied" role="status">
    <TriangleAlert size={16} />
    <div>
      {#if game.emptied.state === 'fetching'}
        <strong>{$t('game.header.emptied.fetchingTitle')}</strong>
        {$t('game.header.emptied.fetchingBody')}
      {:else}
        <strong>{$t('game.header.emptied.title', { where: emptiedWhere(game.emptied.locations) })}</strong>
        {$t('game.header.emptied.body')}
        {#if game.emptied.putBackFrom}
          {$t('game.header.emptied.choiceFromSnapshot', { when: whenLabel(game.emptied.putBackFromTime) })}
        {:else}
          {$t('game.header.emptied.choiceFromDevices')}
        {/if}
        <div class="emptied-actions">
          <button class="btn small primary" disabled={answering} on:click={() => answerEmptied('restore')}>
            <RotateCcw size={13} />{$t('game.header.emptied.putBack')}
          </button>
          <button class="btn small" disabled={answering} on:click={() => answerEmptied('delete')}>
            {$t('game.header.emptied.deleteThere')}
          </button>
        </div>
      {/if}
    </div>
  </div>
{/if}

{#if game.savePathMissing && !editPath}
  <!-- The folder is not there. It is not created again: an empty folder in
       its place would read as every file deleted, and syncing that would
       delete them on your other devices too. -->
  <div class="missing" role="status">
    <TriangleAlert size={16} />
    <div>
      <strong>{$t('game.header.missing.title')}</strong>
      {$t('game.header.missing.body')}
    </div>
  </div>
{/if}

<style>
  .title-row {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .star {
    color: var(--text-faint);
  }
  .star.on {
    color: var(--warn);
  }
  .star.on :global(svg) {
    fill: currentColor;
  }
  .head {
    display: flex;
    align-items: center;
    gap: 14px;
    margin-bottom: 6px;
  }
  .back {
    font-size: 1rem;
  }
  .head-cover {
    height: 52px;
    aspect-ratio: 460 / 215;
    object-fit: cover;
    border-radius: 8px;
    border: 1px solid var(--border);
  }
  .title-block {
    flex: 1;
    min-width: 0;
  }
  .sub {
    color: var(--text-dim);
    font-size: 0.85rem;
    margin-top: 2px;
  }
  .syncing {
    color: var(--accent-text);
    font-weight: 600;
  }
  .synced-with {
    display: flex;
    flex-wrap: wrap;
    gap: 0 6px;
  }
  .synced-with .device {
    white-space: nowrap;
  }
  .synced-with .device > span {
    color: var(--text);
  }
  .synced-with .device > span.never {
    color: var(--text-dim);
    font-style: italic;
  }
  .synced-with .sep {
    color: var(--text-dim);
  }
  .head-actions {
    display: flex;
    gap: 8px;
  }
  .missing {
    display: flex;
    gap: 10px;
    margin: -8px 0 18px 50px;
    padding: 10px 12px;
    border: 1px solid rgba(var(--warn-rgb), 0.35);
    border-radius: var(--radius);
    background: rgba(var(--warn-rgb), 0.08);
    font-size: 0.84rem;
    color: var(--text-dim);
  }
  .missing :global(svg) {
    flex-shrink: 0;
    margin-top: 2px;
    color: var(--warn);
  }
  .missing strong {
    color: var(--text);
  }
  .emptied-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 10px;
  }
  .path-line {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 18px 50px;
  }
  .path {
    font-size: 0.8rem;
    color: var(--text-faint);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .path-input {
    flex: 1;
    padding: 6px 10px;
    background: var(--bg);
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    color: var(--text);
    font-size: 0.82rem;
    outline: none;
  }
  .playing {
    color: var(--success);
    font-weight: 600;
  }
</style>
