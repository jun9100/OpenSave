<script>
  // One cloud version from another device, mapped onto this device's save
  // locations. The plan request is read-only; applying writes only local files
  // and leaves every cloud scope's head untouched.
  import { onMount } from 'svelte';
  import CloudDownload from 'lucide-svelte/icons/cloud-download';
  import ShieldAlert from 'lucide-svelte/icons/shield-alert';
  import Modal from '../../components/ui/Modal.svelte';
  import Spinner from '../../components/ui/Spinner.svelte';
  import { api } from '../../lib/api.js';
  import { askConfirm } from '../../lib/stores.js';
  import { fmtSize } from '../../lib/format.js';
  import { t, trError } from '../../lib/i18n.js';
  import { assignmentsFrom, initialChoices, localLabel, memberTitle, platformLabel, validateChoices } from '../../lib/crossrestore.js';

  export let game;
  export let member;
  export let snapshot;
  export let runner;
  export let onClose = () => {};
  export let onDone = () => {};

  const { busy, run } = runner;

  let plan = null;
  let choices = {};
  let loading = true;
  let error = '';
  let errorKey = '';

  onMount(() => {
    loadPlan();
  });

  async function loadPlan() {
    loading = true;
    error = '';
    plan = null;
    try {
      const next = await api.post(`/api/cloud/cross-restore/${game.id}/plan`, {
        memberGameId: member.gameId,
        snapshotId: snapshot.id
      });
      // Set the choices first so the selects open on the suggested mapping.
      choices = initialChoices(next);
      plan = next;
    } catch (e) {
      error = e.message;
    } finally {
      loading = false;
    }
  }

  $: errorKey = plan ? validateChoices(plan, choices) : '';
  $: sourceDevice = plan?.deviceName || memberTitle(member);
  $: sourcePlatform = platformLabel(plan?.platform || member.platform);

  function timestamp(value) {
    if (!value) return '-';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
  }

  async function apply() {
    if (!plan || errorKey || $busy) return;
    if (
      !(await askConfirm($t('game.cloud.others.restore_confirm', { device: sourceDevice }), {
        title: $t('game.cloud.others.restore_title'),
        confirmText: $t('game.cloud.others.restore_action')
      }))
    )
      return;
    await run($t('game.cloud.others.restore_success'), async () => {
      await api.post(`/api/cloud/cross-restore/${game.id}/apply`, {
        memberGameId: member.gameId,
        snapshotId: snapshot.id,
        assignments: assignmentsFrom(choices)
      });
      onDone();
      onClose();
    });
  }
</script>

<Modal
  title={$t('game.cloud.others.dialog_title')}
  icon={CloudDownload}
  {onClose}
  closable={!$busy}
  width={720}
  height="auto"
  maxHeight="min(85vh, 780px)"
>
  <svelte:fragment slot="sub">{game.name}</svelte:fragment>
  <div class="body">
    {#if loading}
      <p class="quiet"><Spinner size={13} /> {$t('game.cloud.others.plan_loading')}</p>
    {:else if error}
      <div class="plan-failed">
        <p>{$t('game.cloud.others.plan_failed', { error: trError(error) })}</p>
        <div class="actions">
          <button class="btn" on:click={onClose}>{$t('common.cancel')}</button>
          <button class="btn primary" on:click={loadPlan}>{$t('game.cloud.others.retry')}</button>
        </div>
      </div>
    {:else if plan}
      <div class="source-grid">
        <div>
          <span>{$t('game.cloud.others.source')}</span>
          <strong>{sourceDevice}</strong>
        </div>
        <div>
          <span>{$t('game.cloud.others.platform')}</span>
          <strong>{sourcePlatform || '-'}</strong>
        </div>
        <div>
          <span>{$t('game.cloud.others.version_time')}</span>
          <strong>{timestamp(plan.createdAt || snapshot.createdAt)}</strong>
        </div>
      </div>

      <p class="format">{plan.format === 'android' ? $t('game.cloud.others.format_android') : $t('game.cloud.others.format_pc')}</p>

      {#if (plan.ignoredEntries ?? 0) > 0}
        <p class="ignored"><ShieldAlert size={15} /> {$t('game.cloud.others.ignored', { n: plan.ignoredEntries })}</p>
      {/if}

      <div class="locations">
        {#each plan.archiveLocations ?? [] as location (location.id)}
          <div class="location">
            <div class="location-info">
              <div class="location-title">
                <span class="badge offline">{location.kind || '—'}</span>
                <code title={location.relativePath}>{location.relativePath || '/'}</code>
              </div>
              <span class="location-meta">
                {$t('game.cloud.others.file_count', { n: location.files ?? 0 })} · {fmtSize(location.bytes)}
              </span>
            </div>
            <label class="field">
              <span>{$t('game.cloud.others.destination')}</span>
              <select bind:value={choices[location.id]}>
                <option value={null}>{$t('game.cloud.others.skip')}</option>
                {#each plan.localLocations ?? [] as local (local.root)}
                  <option value={local.root}>{localLabel(local, $t)} — {local.path}</option>
                {/each}
              </select>
            </label>
          </div>
        {/each}
      </div>

      <p class="notice"><ShieldAlert size={16} /> <span>{$t('game.cloud.others.notice')}</span></p>

      {#if errorKey}<p class="error" role="alert">{$t(errorKey)}</p>{/if}

      <div class="actions">
        <button class="btn" disabled={$busy} on:click={onClose}>{$t('common.cancel')}</button>
        <button class="btn primary" disabled={$busy || !!errorKey} on:click={apply}>
          <CloudDownload size={14} />{$t('game.cloud.others.restore_action')}
        </button>
      </div>
    {/if}
  </div>
</Modal>

<style>
  .body {
    padding: 14px 22px 20px;
    overflow: auto;
  }
  .quiet {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 0.84rem;
    color: var(--text-dim);
  }
  .plan-failed p {
    font-size: 0.84rem;
    color: var(--text-dim);
    line-height: 1.5;
  }
  .source-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    border-top: 1px solid var(--border);
    border-bottom: 1px solid var(--border);
    margin-bottom: 12px;
  }
  .source-grid > div {
    padding: 9px 0;
    min-width: 0;
  }
  .source-grid span {
    display: block;
    font-size: 0.7rem;
    color: var(--text-faint);
    margin-bottom: 3px;
  }
  .source-grid strong {
    display: block;
    font-size: 0.82rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .format {
    font-size: 0.82rem;
    color: var(--text-dim);
    line-height: 1.5;
    margin-bottom: 10px;
  }
  .ignored {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 0.8rem;
    color: var(--warn);
    line-height: 1.45;
    margin-bottom: 10px;
  }
  .ignored :global(svg) {
    flex-shrink: 0;
  }
  .locations {
    border-top: 1px solid var(--border);
  }
  .location {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(220px, 0.9fr);
    gap: 14px;
    align-items: end;
    padding: 10px 0;
    border-bottom: 1px solid var(--border);
  }
  .location-info {
    min-width: 0;
  }
  .location-title {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .location-title code {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 0.78rem;
  }
  .location-meta {
    display: block;
    font-size: 0.75rem;
    color: var(--text-faint);
    margin-top: 5px;
  }
  .location .field {
    margin-bottom: 0;
    min-width: 0;
  }
  .location .field > span {
    font-size: 0.72rem;
    color: var(--text-faint);
  }
  .location select {
    width: 100%;
    min-width: 0;
  }
  .notice {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    border-left: 3px solid var(--accent);
    background: var(--accent-soft);
    padding: 10px 12px;
    margin: 14px 0;
    font-size: 0.8rem;
    color: var(--text-dim);
    line-height: 1.5;
  }
  .notice :global(svg) {
    color: var(--accent-text);
    flex-shrink: 0;
    margin-top: 1px;
  }
  .error {
    color: var(--warn);
    font-size: 0.8rem;
    margin-bottom: 10px;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 14px;
  }
  @media (max-width: 620px) {
    .source-grid {
      grid-template-columns: 1fr;
    }
    .source-grid > div {
      border-top: 1px solid var(--border);
    }
    .source-grid > div:first-child {
      border-top: none;
    }
    .location {
      grid-template-columns: 1fr;
      align-items: stretch;
    }
  }
</style>
