<script>
  // How to use OpenSave, from installing it to batch actions, in the app's
  // own language (lib/help.js). A contents list down the side, and a filter
  // for finding the section that mentions a word.
  import { t, shownLocale } from '../lib/i18n.js';
  import { helpFor } from '../lib/help.js';
  import { navigate } from '../lib/stores.js';
  import Lightbulb from 'lucide-svelte/icons/lightbulb';

  export let params = {};

  let query = '';
  const text = (s) =>
    [s.title, ...s.blocks.flatMap((b) => [b.p, b.tip, ...(b.steps ?? [])])].filter(Boolean).join(' ').toLowerCase();
  $: q = query.trim().toLowerCase();
  $: HELP = helpFor($shownLocale);
  $: shown = q ? HELP.filter((s) => text(s).includes(q)) : HELP;

  let body;
  function jump(id) {
    body?.querySelector(`#help-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
  // Opened at a section (the setup guide, a hint elsewhere) once it is drawn.
  $: if (body && params.section) setTimeout(() => jump(params.section), 0);
</script>

<div class="page">
  <header class="head">
    <div>
      <h2 class="page-title">{$t('help.title')}</h2>
      <p class="sub">{$t('help.subtitle')}</p>
    </div>
    <input class="search" type="search" data-find placeholder={$t('help.searchPlaceholder')} bind:value={query} aria-label={$t('help.searchLabel')} />
  </header>

  <div class="layout">
    <nav class="toc card" aria-label={$t('help.contents')}>
      {#each HELP as s}
        <button class="linklike" class:dim={q && !shown.includes(s)} on:click={() => jump(s.id)}>{s.title}</button>
      {/each}
      <hr />
      <button class="linklike" on:click={() => navigate('cloud')}>{$t('help.openCloud')}</button>
      <button class="linklike" on:click={() => navigate('settings')}>{$t('help.openSettings')}</button>
    </nav>

    <div class="sections" bind:this={body}>
      {#each shown as s (s.id)}
        <section class="card" id="help-{s.id}">
          <h3>{s.title}</h3>
          {#each s.blocks as b}
            {#if b.p}<p>{b.p}</p>{/if}
            {#if b.steps}
              <ol>
                {#each b.steps as step}<li>{step}</li>{/each}
              </ol>
            {/if}
            {#if b.tip}<div class="tip"><Lightbulb size={15} /><span>{b.tip}</span></div>{/if}
          {/each}
        </section>
      {:else}
        <p class="none">{$t('help.noMatch', { query: query.trim() })}</p>
      {/each}
    </div>
  </div>
</div>

<style>
  .head {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    gap: 16px;
    flex-wrap: wrap;
    margin-bottom: 18px;
  }
  .sub {
    color: var(--text-dim);
    font-size: 0.88rem;
    margin-top: 4px;
    max-width: 640px;
  }
  .search {
    width: 240px;
    padding: 7px 11px;
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
  .layout {
    display: grid;
    grid-template-columns: 200px minmax(0, 1fr);
    gap: 18px;
    align-items: start;
  }
  @media (max-width: 820px) {
    .layout {
      grid-template-columns: 1fr;
    }
    .toc {
      position: static;
    }
  }
  .toc {
    position: sticky;
    top: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 14px 16px;
    font-size: 0.86rem;
  }
  .toc button {
    text-align: left;
  }
  .toc .dim {
    opacity: 0.45;
  }
  .toc hr {
    border: none;
    border-top: 1px solid var(--border);
    margin: 4px 0;
  }
  .sections {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  section {
    padding: 18px 22px;
    scroll-margin-top: 12px;
  }
  h3 {
    font-size: 1.05rem;
    margin-bottom: 10px;
  }
  p,
  li {
    color: var(--text-dim);
    font-size: 0.9rem;
    line-height: 1.65;
  }
  p + p,
  p + ol,
  ol + p {
    margin-top: 8px;
  }
  ol {
    padding-left: 22px;
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 6px 0;
  }
  .tip {
    display: flex;
    gap: 9px;
    align-items: flex-start;
    margin-top: 10px;
    padding: 10px 12px;
    border-radius: 9px;
    background: var(--accent-soft);
    color: var(--text);
    font-size: 0.86rem;
    line-height: 1.6;
  }
  .tip :global(svg) {
    flex: none;
    margin-top: 3px;
    color: var(--accent-text);
  }
  .none {
    color: var(--text-faint);
  }
</style>
