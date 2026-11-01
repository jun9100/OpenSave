<script>
  // A batch from the library's selection (lib/bulk.js): while it runs, how far
  // it has got and which game it is on, with a way to stop; once it is done,
  // what happened to each game — done, failed and why, or skipped and why.
  import { t } from '../lib/i18n.js';
  import { bulkJob, stopBulk, startBulk, tally, BULK_OPS } from '../lib/bulk.js';
  import { navigate, toast } from '../lib/stores.js';
  import Modal from './ui/Modal.svelte';
  import ModalFoot from './ui/ModalFoot.svelte';
  import ProgressBar from './ui/ProgressBar.svelte';
  import ListChecks from 'lucide-svelte/icons/list-checks';
  import CircleCheck from 'lucide-svelte/icons/circle-check';
  import CircleX from 'lucide-svelte/icons/circle-x';
  import CircleMinus from 'lucide-svelte/icons/circle-minus';

  $: job = $bulkJob;
  $: counts = job?.finished ? tally(job.results) : { ok: 0, failed: 0, skipped: 0 };
  // Problems first: they are what the summary is for.
  const ORDER = { failed: 0, skipped: 1, ok: 2 };
  let show = 'all';
  $: if (!job) show = 'all';
  $: rows = (job?.results ?? []).filter((r) => show === 'all' || r.status === show).sort((a, b) => ORDER[a.status] - ORDER[b.status]);

  const STATUS = {
    ok: { icon: CircleCheck, label: (t) => t('bulk.status.ok'), tone: 'ok' },
    failed: { icon: CircleX, label: (t) => t('bulk.status.failed'), tone: 'bad' },
    skipped: { icon: CircleMinus, label: (t) => t('bulk.status.skipped'), tone: 'dim' }
  };

  const close = () => bulkJob.set(null);

  function retryFailed() {
    const failed = job.results.filter((r) => r.status === 'failed').map((r) => r.game);
    const op = job.op;
    bulkJob.set(null);
    startBulk(op, failed);
  }

  async function copyReport() {
    const lines = job.results.map((r) => `${STATUS[r.status].label($t)}\t${r.game.name}${r.note ? `\t${r.note}` : ''}`);
    const text = [`${job.label}: ${$t('bulk.summary', { ok: counts.ok, failed: counts.failed, skipped: counts.skipped })}`, ...lines].join('\n');
    try {
      await navigator.clipboard.writeText(text);
      toast($t('bulk.copied'), 'success');
    } catch (e) {
      toast($t('bulk.copyFailed', { reason: e.message }), 'error');
    }
  }

  function openGame(game) {
    close();
    navigate('game', { gameId: game.id });
  }
</script>

{#if job}
  <Modal title={job.finished ? $t('bulk.finished', { op: BULK_OPS[job.op].label }) : job.label} icon={ListChecks} onClose={close} closable={job.finished} width={620} height="auto" maxHeight="min(80vh, 680px)">
    <svelte:fragment slot="sub">
      {#if job.finished}
        {$t('bulk.summary', { ok: counts.ok, failed: counts.failed, skipped: counts.skipped })}
      {:else}
        {$t('bulk.position', { at: Math.min(job.done + 1, job.total), total: job.total })}
      {/if}
    </svelte:fragment>

    {#if !job.finished}
      <ProgressBar done={job.done} total={job.total}>
        {job.stopping ? $t('bulk.stopping') : $t('bulk.working', { at: Math.min(job.done + 1, job.total), total: job.total })}
        {#if job.current}&nbsp;— <strong>{job.current}</strong>{/if}
      </ProgressBar>
      <p class="note">{$t('bulk.oneAtATime')}</p>
    {:else}
      <div class="tally" role="group" aria-label={$t('bulk.showLabel')}>
        <button class="chip" class:on={show === 'all'} on:click={() => (show = 'all')}>{$t('bulk.status.all')} <span>{job.results.length}</span></button>
        <button class="chip ok" class:on={show === 'ok'} on:click={() => (show = 'ok')}><CircleCheck size={13} />{$t('bulk.status.ok')} <span>{counts.ok}</span></button>
        <button class="chip bad" class:on={show === 'failed'} on:click={() => (show = 'failed')}><CircleX size={13} />{$t('bulk.status.failed')} <span>{counts.failed}</span></button>
        <button class="chip dim" class:on={show === 'skipped'} on:click={() => (show = 'skipped')}><CircleMinus size={13} />{$t('bulk.status.skipped')} <span>{counts.skipped}</span></button>
      </div>
      <ul class="results">
        {#each rows as r (r.game.id)}
          <li class={STATUS[r.status].tone}>
            <svelte:component this={STATUS[r.status].icon} size={15} />
            <button class="linklike name" on:click={() => openGame(r.game)} title={$t('bulk.openGame', { name: r.game.name })}>{r.game.name}</button>
            <span class="why">{r.note || STATUS[r.status].label($t)}</span>
          </li>
        {:else}
          <li class="dim empty">{$t('bulk.nothingHere')}</li>
        {/each}
      </ul>
    {/if}

    <ModalFoot>
      <span class="note">
        {#if job.finished && counts.failed}{$t('bulk.failedFirst')}{/if}
      </span>
      <div class="actions">
        {#if !job.finished}
          <button class="btn" disabled={job.stopping} on:click={stopBulk}>{$t('bulk.stop')}</button>
        {:else}
          <button class="btn" on:click={copyReport}>{$t('bulk.copy')}</button>
          {#if counts.failed}<button class="btn" on:click={retryFailed}>{$t('bulk.retryFailed', { n: counts.failed })}</button>{/if}
          <button class="btn primary" on:click={close}>{$t('bulk.close')}</button>
        {/if}
      </div>
    </ModalFoot>
  </Modal>
{/if}

<style>
  .note {
    color: var(--text-faint);
    font-size: 0.82rem;
    padding: 0 22px 12px;
  }
  :global(.foot) .note {
    padding: 0;
  }
  .actions {
    display: flex;
    gap: 8px;
  }
  .tally {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    padding: 14px 22px 6px;
  }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 4px 11px;
    border-radius: 999px;
    border: 1px solid var(--border-strong);
    background: transparent;
    color: var(--text-dim);
    font-size: 0.82rem;
    cursor: pointer;
  }
  .chip span {
    font-weight: 700;
    color: var(--text);
  }
  .chip.on {
    border-color: var(--accent);
    background: var(--accent-soft);
    color: var(--text);
  }
  .chip.ok :global(svg) {
    color: var(--success, #22c55e);
  }
  .chip.bad :global(svg) {
    color: var(--danger, #ef4444);
  }
  .results {
    list-style: none;
    margin: 0;
    padding: 6px 22px 14px;
    overflow-y: auto;
    max-height: min(46vh, 420px);
  }
  .results li {
    display: grid;
    grid-template-columns: 18px minmax(0, 0.9fr) minmax(0, 1.6fr);
    align-items: center;
    gap: 10px;
    padding: 7px 0;
    border-bottom: 1px solid var(--border);
    font-size: 0.86rem;
  }
  .results li.empty {
    display: block;
    color: var(--text-faint);
  }
  .results li.ok :global(svg) {
    color: var(--success, #22c55e);
  }
  .results li.bad :global(svg) {
    color: var(--danger, #ef4444);
  }
  .results li.dim :global(svg) {
    color: var(--text-faint);
  }
  .name {
    background: none;
    border: none;
    padding: 0;
    color: var(--text);
    cursor: pointer;
    text-align: left;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 600;
  }
  .why {
    color: var(--text-dim);
    overflow-wrap: anywhere;
  }
  .results li.bad .why {
    color: var(--danger-text, var(--danger, #ef4444));
  }
</style>
