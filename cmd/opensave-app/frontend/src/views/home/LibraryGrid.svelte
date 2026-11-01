<script context="module">
  // The tiles come in one after another the first time the library is shown
  // in a session; after that the page's own arrival is enough, and coming
  // back to Home is not made to wait.
  let shownBefore = false;
</script>

<script>
  // The tracked games, each saying where its save stands — as wide banners or
  // tall box art, as many to a row as chosen, filtered by name and by status.
  // "Select" turns the grid into a multi-select, to act on several games at
  // once (e.g. clear a batch of wrongly-tracked entries) without going in and
  // out of each one.
  import { navigate, settings, syncPause } from '../../lib/stores.js';
  import { t } from '../../lib/i18n.js';
  import { api } from '../../lib/api.js';
  import Camera from 'lucide-svelte/icons/camera';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import { SORTS, sortRows } from '../../lib/gamestatus.js';
  import ArrowDownWideNarrow from 'lucide-svelte/icons/arrow-down-wide-narrow';
  import ArrowUpNarrowWide from 'lucide-svelte/icons/arrow-up-narrow-wide';
  import { libraryView, gridColumns, filterRows, offeredFilters } from '../../lib/libraryview.js';
  import { backdropClose } from '../../lib/backdrop.js';
  import FilterTabs from '../../components/ui/FilterTabs.svelte';
  import LibraryTile from './LibraryTile.svelte';
  import LibraryViewOptions from './LibraryViewOptions.svelte';
  import { bulkJob, startBulk, BULK_OPS, bulkUnavailable } from '../../lib/bulk.js';
  import CloudUpload from 'lucide-svelte/icons/cloud-upload';
  import History from 'lucide-svelte/icons/history';
  import RefreshCcwDot from 'lucide-svelte/icons/refresh-ccw-dot';
  import PauseCircle from 'lucide-svelte/icons/circle-pause';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import { collections, collectionFilter, collectionChips, inCollection } from '../../lib/collections.js';
  import Star from 'lucide-svelte/icons/star';
  import LayoutGrid from 'lucide-svelte/icons/layout-grid';
  import SquareCheckBig from 'lucide-svelte/icons/square-check-big';

  /** [{game, status}] for every tracked game. */
  export let rows = [];

  const firstShow = !shownBefore;
  shownBefore = true;

  let query = '';
  let status = 'all';

  $: filters = offeredFilters(rows);
  // A filter chosen while it made sense stays chosen only while it still does.
  $: if (status !== 'all' && !filters.some((f) => f.id === status)) status = 'all';
  // Collections narrow the list the same way the status chips do, and the
  // two combine: Favourites that need attention.
  $: chips = collectionChips($collections, rows);
  $: if ($collectionFilter && !$collections.some((c) => c.id === $collectionFilter)) collectionFilter.set('');
  $: shown = sortRows(filterRows(inCollection(rows, $collections, $collectionFilter), { query, status }), $libraryView.sort, $libraryView.reverse);
  $: filtering = query.trim() !== '' || status !== 'all' || !!$collectionFilter;
  const clearFilters = () => {
    query = '';
    status = 'all';
    collectionFilter.set('');
  };
  const toggleCollection = (id) => collectionFilter.set($collectionFilter === id ? '' : id);

  let viewOpen = false;

  let selectMode = false;
  let libSelected = new Set();

  function toggleSelectMode() {
    selectMode = !selectMode;
    if (!selectMode) libSelected = new Set();
  }
  function toggleSelect(id) {
    if (libSelected.has(id)) libSelected.delete(id);
    else libSelected.add(id);
    libSelected = libSelected;
  }

  // Selecting without the Select button: hold a tile down, or Ctrl-click it,
  // and the library is selecting with that game picked. While selecting, a
  // click toggles a game and Shift-click takes in everything from the last
  // one picked. Escape stops.
  let anchor = null;
  function startSelecting(id) {
    selectMode = true;
    libSelected = new Set([id]);
    anchor = id;
  }
  function tileLongPress(id) {
    if (!selectMode) startSelecting(id);
    else {
      toggleSelect(id);
      anchor = id;
    }
  }
  function tileOpen(id, mods = {}) {
    if (!selectMode) {
      if (mods.ctrlKey || mods.metaKey) startSelecting(id);
      else navigate('game', { gameId: id });
      return;
    }
    if (mods.shiftKey && anchor) {
      const order = shown.map((r) => r.game.id);
      const [from, to] = [order.indexOf(anchor), order.indexOf(id)].sort((a, b) => a - b);
      if (from >= 0) {
        for (const pick of order.slice(from, to + 1)) libSelected.add(pick);
        libSelected = libSelected;
        return;
      }
    }
    toggleSelect(id);
    anchor = id;
  }
  // The arrow keys move between games as they are laid out: left and right
  // along a row, up and down by a row's worth, Home and End to either end.
  // Enter opens, as it always did; while selecting it picks.
  function gridKeys(e) {
    const step = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: 'down', ArrowUp: 'up', Home: 'first', End: 'last' }[e.key];
    if (step === undefined || e.altKey || e.ctrlKey || e.metaKey) return;
    const tiles = [...e.currentTarget.querySelectorAll('.tile')];
    const i = tiles.indexOf(document.activeElement);
    if (i < 0) return;
    const perRow = tiles.filter((t) => t.offsetTop === tiles[0].offsetTop).length || 1;
    const j =
      step === 'down' ? i + perRow : step === 'up' ? i - perRow : step === 'first' ? 0 : step === 'last' ? tiles.length - 1 : i + step;
    if (j < 0 || j >= tiles.length || j === i) return;
    e.preventDefault();
    tiles[j].focus();
    tiles[j].scrollIntoView({ block: 'nearest' });
  }

  function stopSelecting() {
    selectMode = false;
    libSelected = new Set();
    anchor = null;
  }

  // Things to do to all of them at once, through lib/bulk.js: one game at a
  // time, with progress and a per-game summary in BulkDialog, and a question
  // first for the ones that replace or remove something. The selection stays,
  // so one batch can follow another; stopping tracking ends it.
  const picked = () => rows.filter((r) => libSelected.has(r.game.id)).map((r) => r.game);
  $: bulkBusy = !!$bulkJob && !$bulkJob.finished;
  $: bulkCtx = { cloudReady: !!$settings?.cloudSync?.ready, paused: !!$syncPause.paused };
  async function bulk(op) {
    const games = picked();
    if (games.length === 0 || bulkBusy) return;
    const results = await startBulk(op, games);
    if (results && op === 'untrack') stopSelecting();
  }
  // Whether cloud backup is set up decides what a batch can offer, and it can
  // change from outside this window (the CLI, another screen): asked afresh
  // each time selecting starts.
  $: if (selectMode) refreshSettings();
  async function refreshSettings() {
    try {
      settings.set(await api.get('/api/settings'));
    } catch {
      // The last known settings are good enough to draw the buttons.
    }
  }
  // "Select all" is what is on screen: selecting games a filter is hiding,
  // to then act on them, would act on things nobody was looking at. The whole
  // library is offered separately, and the count says how many of those
  // picked are out of sight.
  $: allSelected = shown.length > 0 && shown.every((r) => libSelected.has(r.game.id));
  $: hiddenPicked = [...libSelected].filter((id) => !shown.some((r) => r.game.id === id)).length;
  function toggleSelectAll() {
    libSelected = allSelected ? new Set() : new Set(shown.map((r) => r.game.id));
  }
  const selectLibrary = () => (libSelected = new Set(rows.map((r) => r.game.id)));
  function invertSelection() {
    libSelected = new Set(shown.map((r) => r.game.id).filter((id) => !libSelected.has(id)));
  }
  const BULK_BUTTONS = [
    ['snapshot', Camera],
    ['cloud', CloudUpload],
    ['restore', History],
    ['sync', RefreshCw],
    ['enableSync', RefreshCcwDot],
    ['disableSync', PauseCircle],
    ['favourite', Star],
    ['untrack', Trash2]
  ];
</script>

<svelte:window
  on:keydown={(e) => {
    if (e.key !== 'Escape') return;
    if (viewOpen) viewOpen = false;
    else if (selectMode && !document.querySelector('.overlay, .backdrop[role="presentation"], .menu')) stopSelecting();
  }}
/>

<div class="toolbar">
  <h3>{$t('home.library.title')}</h3>
  <input
    class="search"
    type="search"
    data-find
    placeholder={$t('home.library.searchPlaceholder')}
    bind:value={query}
    aria-label={$t('home.library.searchLabel')}
  />
  <div class="spacer"></div>
  {#if selectMode}
    <span class="select-count">
      {$t('home.library.selectedCount', { n: libSelected.size })}{#if hiddenPicked}<span class="hidden-note">{$t('home.library.hiddenPicked', { n: hiddenPicked })}</span>{/if}
    </span>
    <button class="btn small" on:click={toggleSelectAll} title={filtering ? $t('home.library.selectFilteredTitle') : ''}>
      {allSelected ? $t('home.library.unselectAll') : filtering ? $t('home.library.selectFiltered', { count: shown.length }) : $t('home.library.selectAll', { count: shown.length })}
    </button>
    {#if filtering && libSelected.size < rows.length}
      <button class="btn small" on:click={selectLibrary} title={$t('home.library.wholeLibraryTitle')}>{$t('home.library.wholeLibrary', { count: rows.length })}</button>
    {/if}
    <button class="btn small" on:click={invertSelection} disabled={shown.length === 0}>{$t('home.library.invert')}</button>
    <button class="btn small" on:click={stopSelecting}>{$t('home.library.doneSelecting')}</button>
  {:else}
    <label class="sort">
      <span>{$t('home.library.sort')}</span>
      <select bind:value={$libraryView.sort}>
        {#each Object.entries(SORTS) as [id, s]}
          <option value={id}>{s.label}</option>
        {/each}
      </select>
    </label>
    <button
      class="btn small icon order"
      on:click={() => libraryView.update((v) => ({ ...v, reverse: !v.reverse }))}
      title={$libraryView.reverse ? $t('home.library.reversedTitle') : $t('home.library.reverseTitle')}
      aria-label={$t('home.library.reverseTitle')}
      aria-pressed={$libraryView.reverse}
    >
      <svelte:component this={$libraryView.reverse ? ArrowUpNarrowWide : ArrowDownWideNarrow} size={15} />
    </button>
    <div class="view-anchor">
      <button class="btn small" class:active={viewOpen} aria-expanded={viewOpen} on:click={() => (viewOpen = !viewOpen)}>
        <LayoutGrid size={14} />{$t('home.library.view')}
      </button>
      {#if viewOpen}
        <div class="view-backdrop" use:backdropClose={() => (viewOpen = false)} role="presentation"></div>
        <div class="view-panel card" role="dialog" aria-label={$t('home.library.viewLabel')}>
          <LibraryViewOptions />
          <p class="view-hint">{$t('home.library.viewHint')}</p>
        </div>
      {/if}
    </div>
    <button class="btn small" on:click={toggleSelectMode}><SquareCheckBig size={14} />{$t('home.library.select')}</button>
  {/if}
</div>

{#if selectMode}
  <div class="bulkbar" role="toolbar" aria-label={$t('home.library.bulkLabel')}>
    <span class="bulk-lead">{$t('home.library.bulkLead')}</span>
    {#each BULK_BUTTONS as [op, icon]}
      {@const why = bulkUnavailable(op, bulkCtx)}
      <button
        class="btn small"
        class:danger={BULK_OPS[op].danger}
        disabled={libSelected.size === 0 || bulkBusy || !!why}
        title={why || BULK_OPS[op].hint}
        on:click={() => bulk(op)}
      >
        <svelte:component this={icon} size={14} />{BULK_OPS[op].label}
      </button>
    {/each}
  </div>
  <p class="bulk-help">{$t('home.library.bulkHelp')}</p>
{/if}

{#if filters.length > 1 || chips.length}
  <div class="chips">
    {#if filters.length > 1}
      <FilterTabs options={filters.map((f) => [f.id, f.label])} counts={Object.fromEntries(filters.map((f) => [f.id, f.count]))} bind:value={status} />
    {/if}
    {#if chips.length}
      {#if filters.length > 1}<span class="chip-sep" aria-hidden="true"></span>{/if}
      <div class="collection-chips" role="group" aria-label={$t('home.library.collectionsLabel')}>
        {#each chips as c}
          <button class="collection-chip" class:active={$collectionFilter === c.id} aria-pressed={$collectionFilter === c.id} on:click={() => toggleCollection(c.id)}>
            {#if c.builtin}<Star size={12} />{/if}{c.label}<span class="count">{c.count}</span>
          </button>
        {/each}
      </div>
    {/if}
  </div>
{/if}

{#if shown.length === 0}
  <div class="none">
    {#if query.trim()}
      <p>{$t('home.library.emptyFor', { query: query.trim() })}</p>
    {:else}
      <p>{$t('home.library.empty')}</p>
    {/if}
    <button class="btn small" on:click={clearFilters}>{$t('home.library.clearFilters')}</button>
  </div>
{:else}
  <div class="grid" style="grid-template-columns: {gridColumns($libraryView)}" on:keydown={gridKeys} role="presentation">
    {#each shown as { game, status: s }, i (game.id)}
      <LibraryTile
        {game}
        status={s}
        enter={firstShow ? i : -1}
        cover={$libraryView.cover}
        selecting={selectMode}
        selected={libSelected.has(game.id)}
        on:open={(e) => tileOpen(game.id, e.detail)}
        on:longpress={() => tileLongPress(game.id)}
      />
    {/each}
  </div>
  {#if filtering}
    <p class="showing">
      {$t('home.library.showing', { shown: shown.length, total: rows.length })}
      <button class="linklike" on:click={clearFilters}>{$t('home.library.showAll')}</button>
    </p>
  {/if}
{/if}

<style>
  .toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 12px;
    flex-wrap: wrap;
  }
  .toolbar h3 {
    margin-right: 6px;
  }
  .bulkbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    padding: 8px 10px;
    margin-bottom: 6px;
    background: var(--bg-raised);
    border: 1px solid var(--border);
    border-radius: 10px;
  }
  .bulk-lead {
    font-size: 0.8rem;
    color: var(--text-dim);
    margin-right: 4px;
  }
  .bulk-help {
    font-size: 0.78rem;
    color: var(--text-faint);
    margin: 0 2px 12px;
  }
  .hidden-note {
    color: var(--text-faint);
    font-weight: 400;
  }
  .search {
    width: 220px;
    padding: 6px 11px;
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    color: var(--text);
    font-size: 0.86rem;
    outline: none;
  }
  .search:focus {
    border-color: var(--accent);
  }
  .spacer {
    flex: 1;
  }
  .select-count {
    color: var(--text-dim);
    font-size: 0.85rem;
  }
  .sort {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 0.82rem;
    color: var(--text-faint);
  }
  .sort select {
    height: 28px;
    padding: 0 8px 0 10px;
    background-color: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    color: var(--text);
    font-size: 0.85rem;
  }
  .view-anchor {
    position: relative;
  }
  .btn.active {
    background: var(--accent-soft);
    border-color: rgba(var(--accent-rgb), 0.45);
  }
  .view-backdrop {
    position: fixed;
    inset: 0;
    z-index: 30;
  }
  .view-panel {
    position: absolute;
    right: 0;
    top: calc(100% + 8px);
    z-index: 31;
    width: 380px;
    max-width: calc(100vw - 32px);
    padding: 16px;
    box-shadow: var(--shadow);
  }
  .view-hint {
    margin-top: 12px;
    font-size: 0.75rem;
    color: var(--text-faint);
  }
  .chips {
    margin: -2px 0 14px;
  }
  .grid {
    display: grid;
    gap: 12px;
  }
  .none {
    text-align: center;
    color: var(--text-dim);
    padding: 40px 20px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 12px;
  }
  .showing {
    margin-top: 12px;
    font-size: 0.8rem;
    color: var(--text-faint);
  }
  .linklike {
    background: none;
    border: none;
    padding: 0;
    font: inherit;
    color: var(--accent-text);
    cursor: pointer;
  }
  .chips {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
  }
  .chip-sep {
    width: 1px;
    height: 20px;
    background: var(--border-strong);
  }
  .collection-chips {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
  .collection-chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 7px 13px;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: transparent;
    color: var(--text-dim);
    font: inherit;
    font-size: 0.85rem;
    cursor: pointer;
  }
  .collection-chip:hover {
    background: var(--bg-hover);
  }
  .collection-chip.active {
    background: var(--accent-soft);
    border-color: rgba(var(--accent-rgb), 0.45);
    color: var(--text);
  }
  .collection-chip :global(svg) {
    color: var(--warn);
  }
  .collection-chip .count {
    font-size: 0.75rem;
    color: var(--text-faint);
  }
</style>
