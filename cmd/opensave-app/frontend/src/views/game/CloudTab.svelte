<script>
  import { navigate, toast, askConfirm, settings } from '../../lib/stores.js';
  import { api } from '../../lib/api.js';
  import { fmtSize } from '../../lib/format.js';
  import { t } from '../../lib/i18n.js';
  import Spinner from '../../components/ui/Spinner.svelte';
  import CircleAlert from 'lucide-svelte/icons/circle-alert';
  import CircleCheck from 'lucide-svelte/icons/circle-check';
  import Cloud from 'lucide-svelte/icons/cloud';
  import CloudDownload from 'lucide-svelte/icons/cloud-download';
  import History from 'lucide-svelte/icons/history';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import ShieldAlert from 'lucide-svelte/icons/shield-alert';
  import Upload from 'lucide-svelte/icons/upload';

  export let game;
  export let runner;
  const { busy, run } = runner;

  let snaps = null;
  let status = null;
  let loading = false;
  let statusReadFailed = false;

  // `legacy` is the sole compatibility fallback. A failed native status read
  // must not make the UI reopen the timestamped archive path.
  $: native = status?.provider === 'game-save' && status?.state !== 'legacy';
  $: statusTone = native ? toneFor(status.state) : 'muted';
  $: publishHint = native ? nativePublishHint(status) : '';

  async function load() {
    loading = true;
    snaps = null;
    status = null;
    statusReadFailed = false;
    try {
      const nextStatus = await api.get(`/api/cloud/status/${game.id}`);
      status = nextStatus;
      // Native scopes carry their authoritative lineage. Older providers keep
      // the ordinary archive browser, including the staged-server fallback.
      if (nextStatus.provider !== 'game-save' || nextStatus.state === 'legacy') {
        snaps = await api.get(`/api/cloud/snapshots/${game.id}`);
      }
    } catch (e) {
      toast(e.message, 'error');
      statusReadFailed = true;
      snaps = [];
    } finally {
      loading = false;
    }
  }
  load();

  function toneFor(state) {
    if (state === 'up_to_date') return 'success';
    if (state === 'upload_pending' || state === 'download_pending') return 'pending';
    if (state === 'conflict' || state === 'in_use' || state === 'error' || state === 'offline') return 'danger';
    return 'muted';
  }

  function stateLabel(state) {
    return $t(`game.cloud.state.${state || 'error'}`);
  }

  function stateIcon(state) {
    if (state === 'up_to_date') return CircleCheck;
    if (state === 'upload_pending') return Upload;
    if (state === 'download_pending') return CloudDownload;
    if (state === 'conflict') return ShieldAlert;
    return CircleAlert;
  }

  function nativeDetail(current) {
    if (!current) return '';
    if (current.state === 'up_to_date') return $t('game.cloud.detail.up_to_date');
    if (current.state === 'upload_pending') {
      return current.publishable ? $t('game.cloud.detail.publishable') : $t('game.cloud.detail.snapshot_required');
    }
    if (current.state === 'download_pending') return $t('game.cloud.detail.download_pending');
    if (current.state === 'conflict') return $t('game.cloud.detail.conflict');
    if (current.state === 'in_use') return $t('game.cloud.detail.in_use');
    if (current.state === 'disabled') return $t('game.cloud.detail.disabled');
    if (current.state === 'offline') return $t('game.cloud.detail.offline');
    return $t('game.cloud.detail.error');
  }

  function nativePublishHint(current) {
    if (!current?.publishable) return nativeDetail(current);
    return $t('game.cloud.publish.ready');
  }

  function shortID(value) {
    if (!value) return '-';
    return value.length > 14 ? `${value.slice(0, 14)}...` : value;
  }

  function sourceLabel(snapshot) {
    return snapshot?.deviceName || snapshot?.device || $t('game.cloud.unknown_device');
  }

  function timestamp(value) {
    if (!value) return '-';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
  }

  const restore = async (snap) => {
    if (!(await askConfirm($t('game.cloud.restore.confirm', { id: snap.snapshotId }), {
      title: $t('game.cloud.restore.title'),
      confirmText: $t('game.cloud.restore.action')
    }))) return;
    return run($t('game.cloud.restore.success'), async () => {
      await api.post(`/api/cloud/restore/${game.id}`, { fileName: snap.name, id: snap.id ?? '' });
      await load();
    });
  };

  const restoreAuthoritative = async () => {
    const head = status?.cloudSnapshot;
    if (!head || status.restoreBlocked) return;
    if (!(await askConfirm($t('game.cloud.native.restore_confirm', { source: sourceLabel(head) }), {
      title: $t('game.cloud.native.restore_title'),
      confirmText: $t('game.cloud.native.restore_action')
    }))) return;
    return run($t('game.cloud.native.restore_success'), async () => {
      await api.post(`/api/cloud/restore/${game.id}`, { fileName: head.archive, id: status.saveType });
      await load();
    });
  };

  const upload = () =>
    run(native ? $t('game.cloud.native.publish_success') : $t('game.cloud.upload.success'), async () => {
      const res = await api.post(`/api/cloud/sync-local/${game.id}`);
      toast($t('game.cloud.upload.result', { uploaded: res.uploaded, skipped: res.skipped }), 'success');
      await load();
    });
</script>

<div class="card">
  <div class="head">
    <div>
      <h3 class="with-icon"><Cloud size={18} />{$t('game.cloud.title', { name: game.name })}</h3>
      <p class="sub">{statusReadFailed ? $t('game.cloud.status_unavailable') : native ? $t('game.cloud.native.subtitle') : $t('game.cloud.legacy.subtitle')}</p>
    </div>
    <div class="actions">
      <button class="btn small" disabled={$busy || loading} title={$t('game.cloud.refresh')} on:click={load}>
        <RefreshCw size={13} />{$t('game.cloud.refresh')}
      </button>
      {#if native}
        <button class="btn small primary" disabled={$busy || !status.publishable} title={publishHint} on:click={upload}>
          <Upload size={13} />{$t('game.cloud.native.publish')}
        </button>
      {:else if !statusReadFailed}
        <button class="btn small primary" disabled={$busy || loading} on:click={upload}>
          <Upload size={13} />{$t('game.cloud.upload.action')}
        </button>
      {/if}
    </div>
  </div>

  {#if loading}
    <div class="quiet-block"><Spinner size={13} /> {$t('game.cloud.loading')}</div>
  {:else if statusReadFailed}
    <section class="native-status tone-danger" aria-live="polite">
      <div class="state-icon"><CircleAlert size={21} /></div>
      <div class="state-copy">
        <div class="state-topline"><strong>{$t('game.cloud.state.error')}</strong></div>
        <p>{$t('game.cloud.status_unavailable')}</p>
      </div>
    </section>
  {:else if native}
    <section class="native-status tone-{statusTone}" aria-live="polite">
      <div class="state-icon"><svelte:component this={stateIcon(status.state)} size={21} /></div>
      <div class="state-copy">
        <div class="state-topline">
          <strong>{stateLabel(status.state)}</strong>
          <span class="badge offline">{status.saveType}</span>
          {#if !status.automatic}<span class="badge warn">{$t('game.cloud.auto_off')}</span>{/if}
        </div>
        <p>{nativeDetail(status)}</p>
      </div>
    </section>

    <div class="lineage-grid" aria-label={$t('game.cloud.native.lineage')}>
      <div>
        <span>{$t('game.cloud.native.namespace')}</span>
        <code>{status.saveType}/{game.id}</code>
      </div>
      <div>
        <span>{$t('game.cloud.native.authoritative_head')}</span>
        <code title={status.cloudHead}>{shortID(status.cloudHead)}</code>
      </div>
      <div>
        <span>{$t('game.cloud.native.source')}</span>
        <strong>{sourceLabel(status.cloudSnapshot)}</strong>
      </div>
      <div>
        <span>{$t('game.cloud.native.local_head')}</span>
        <code title={status.localConfirmedHead}>{shortID(status.localConfirmedHead)}</code>
      </div>
    </div>

    <section class="scope-section authoritative">
      <div class="section-head">
        <div>
          <h4>{$t('game.cloud.native.authoritative_head')}</h4>
          {#if status.cloudSnapshot}
            <p>{sourceLabel(status.cloudSnapshot)} · {timestamp(status.cloudSnapshot.receivedAt)}</p>
          {:else}
            <p>{$t('game.cloud.native.no_head')}</p>
          {/if}
        </div>
        {#if status.cloudSnapshot}
          <button class="btn small primary" disabled={$busy || status.restoreBlocked} title={status.restoreBlocked ? nativeDetail(status) : $t('game.cloud.native.restore_action')} on:click={restoreAuthoritative}>
            <CloudDownload size={14} />{$t('game.cloud.native.restore_action')}
          </button>
        {/if}
      </div>
      {#if status.cloudSnapshot}
        <div class="head-row">
          <code title={status.cloudSnapshot.id}>{shortID(status.cloudSnapshot.id)}</code>
          <span>{status.cloudSnapshot.platform || '-'}</span>
          <span>{timestamp(status.cloudSnapshot.receivedAt)}</span>
        </div>
      {/if}
    </section>

    <section class="scope-section">
      <div class="section-head compact">
        <div class="section-title"><History size={16} /><h4>{$t('game.cloud.native.history')}</h4></div>
        <span class="badge offline">{status.snapshotCount}</span>
      </div>
      {#if status.snapshots?.length}
        <div class="version-list">
          {#each status.snapshots as snap (snap.id)}
            <div class:head-version={snap.id === status.cloudHead} class="version-row">
              <div class="version-primary">
                <code title={snap.id}>{shortID(snap.id)}</code>
                {#if snap.id === status.cloudHead}<span class="badge online">{$t('game.cloud.native.authoritative')}</span>{/if}
              </div>
              <span>{sourceLabel(snap)}</span>
              <span>{timestamp(snap.receivedAt)}</span>
            </div>
          {/each}
        </div>
      {:else}
        <p class="quiet-block inline">{$t('game.cloud.native.no_versions')}</p>
      {/if}
    </section>

    <section class:has-conflicts={status.conflictCount > 0} class="scope-section conflicts">
      <div class="section-head compact">
        <div class="section-title"><ShieldAlert size={16} /><h4>{$t('game.cloud.native.conflicts')}</h4></div>
        <span class:warn={status.conflictCount > 0} class="badge offline">{status.conflictCount}</span>
      </div>
      {#if status.conflicts?.length}
        <div class="version-list">
          {#each status.conflicts as conflict (conflict.id)}
            <div class="version-row conflict-row">
              <div class="version-primary"><code title={conflict.id}>{shortID(conflict.id)}</code></div>
              <span>{sourceLabel(conflict)}</span>
              <span>{timestamp(conflict.receivedAt)}</span>
            </div>
          {/each}
        </div>
      {:else}
        <p class="quiet-block inline">{$t('game.cloud.native.no_conflicts')}</p>
      {/if}
    </section>
  {:else if !snaps || snaps.length === 0}
    <div class="quiet-block">
      <p>{$t('game.cloud.legacy.empty')}</p>
      {#if $settings?.cloudSync?.ready}
        <p class="hint">{$t('game.cloud.legacy.ready_hint')}</p>
      {:else}
        <p class="hint">
          {$t('game.cloud.legacy.configure_prefix')}
          <button class="linklike" on:click={() => navigate('cloud')}>{$t('game.cloud.legacy.configure_link')}</button>
          {$t('game.cloud.legacy.configure_suffix')}
        </p>
      {/if}
    </div>
  {:else}
    <table>
      <thead>
        <tr><th>{$t('game.cloud.legacy.branch')}</th><th>{$t('game.cloud.legacy.date')}</th><th>{$t('game.cloud.legacy.size')}</th><th></th></tr>
      </thead>
      <tbody>
        {#each snaps as snap (snap.name)}
          <tr>
            <td><span class="badge offline">{snap.branch}</span></td>
            <td class="mono">{timestamp(snap.createdTime)}</td>
            <td class="mono">{fmtSize(snap.sizeBytes)}</td>
            <td class="right">
              <button class="btn small primary" disabled={$busy} on:click={() => restore(snap)}>{$t('game.cloud.restore.action')}</button>
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</div>

<style>
  .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
  .sub { font-size: 0.82rem; color: var(--text-faint); margin-top: 3px; }
  .actions { display: flex; gap: 8px; flex-wrap: wrap; }
  .quiet-block { padding: 30px 10px; text-align: center; color: var(--text-faint); }
  .quiet-block.inline { padding: 12px 0 2px; text-align: left; }
  .hint { font-size: 0.82rem; margin-top: 6px; }
  .linklike { background: none; border: none; color: var(--accent-text); cursor: pointer; padding: 0; font: inherit; }
  .native-status { display: flex; gap: 12px; align-items: flex-start; border: 1px solid var(--border); border-left-width: 3px; padding: 12px; margin-bottom: 12px; }
  .native-status.tone-success { border-left-color: var(--success); }
  .native-status.tone-pending { border-left-color: var(--accent); }
  .native-status.tone-danger { border-left-color: var(--warn); }
  .native-status.tone-muted { border-left-color: var(--text-faint); }
  .state-icon { color: var(--text-dim); flex: 0 0 auto; padding-top: 1px; }
  .tone-success .state-icon { color: var(--success); }
  .tone-pending .state-icon { color: var(--accent-text); }
  .tone-danger .state-icon { color: var(--warn); }
  .state-copy { min-width: 0; }
  .state-topline { display: flex; gap: 7px; align-items: center; flex-wrap: wrap; }
  .state-copy p { color: var(--text-dim); font-size: 0.82rem; line-height: 1.45; margin-top: 4px; }
  .lineage-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); margin-bottom: 14px; }
  .lineage-grid > div { padding: 10px; min-width: 0; border-right: 1px solid var(--border); }
  .lineage-grid > div:last-child { border-right: none; }
  .lineage-grid span { display: block; font-size: 0.7rem; color: var(--text-faint); margin-bottom: 4px; }
  .lineage-grid code, .lineage-grid strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.78rem; }
  .scope-section { border-top: 1px solid var(--border); padding: 12px 0; }
  .scope-section.authoritative { border-top: none; padding-top: 0; }
  .scope-section.conflicts.has-conflicts { border-top-color: var(--warn); }
  .section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
  .section-head.compact { margin-bottom: 7px; }
  .section-head h4 { font-size: 0.86rem; font-weight: 650; }
  .section-head p { font-size: 0.77rem; color: var(--text-faint); margin-top: 3px; }
  .section-title { display: flex; gap: 7px; align-items: center; color: var(--text-dim); }
  .head-row, .version-row { display: grid; grid-template-columns: minmax(130px, 1.25fr) minmax(105px, 0.9fr) minmax(140px, 1fr); gap: 10px; align-items: center; min-height: 36px; padding: 7px 9px; border-top: 1px solid var(--border); font-size: 0.76rem; color: var(--text-dim); }
  .head-row { margin-top: 10px; }
  .version-list { border-bottom: 1px solid var(--border); }
  .version-row:first-child { border-top: 1px solid var(--border); }
  .head-version { background: rgba(var(--success-rgb), 0.06); }
  .conflict-row { background: rgba(var(--warn-rgb), 0.06); }
  .version-primary { display: flex; align-items: center; min-width: 0; gap: 7px; }
  .version-primary code, .head-row code { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
  th { text-align: left; color: var(--text-faint); font-weight: 600; font-size: 0.75rem; padding: 6px 10px; border-bottom: 1px solid var(--border); }
  td { padding: 9px 10px; border-bottom: 1px solid var(--border); }
  tr:last-child td { border-bottom: none; }
  .mono { font-family: 'Cascadia Code', 'Consolas', monospace; font-size: 0.78rem; color: var(--text-dim); }
  .right { text-align: right; }
  @media (max-width: 720px) {
    .lineage-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .lineage-grid > div:nth-child(2) { border-right: none; }
    .lineage-grid > div:nth-child(n + 3) { border-top: 1px solid var(--border); }
    .head-row, .version-row { grid-template-columns: minmax(0, 1fr) minmax(100px, auto); }
    .head-row > :last-child, .version-row > :last-child { grid-column: 1 / -1; color: var(--text-faint); }
  }
  @media (max-width: 460px) {
    .actions { width: 100%; }
    .actions .btn { flex: 1; justify-content: center; }
    .lineage-grid { grid-template-columns: 1fr; }
    .lineage-grid > div { border-right: none; border-top: 1px solid var(--border); }
    .lineage-grid > div:first-child { border-top: none; }
    .section-head:not(.compact) { align-items: stretch; flex-direction: column; }
    .section-head:not(.compact) .btn { width: 100%; justify-content: center; }
  }
</style>
