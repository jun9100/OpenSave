<script>
  // This game's saves on other devices. Read-only: restoring one goes through
  // CrossRestoreDialog, which writes only local files and leaves every cloud
  // scope's head alone.
  import { onMount } from 'svelte';
  import { api } from '../../lib/api.js';
  import { t, trError } from '../../lib/i18n.js';
  import { memberTitle, platformLabel, sortMembers } from '../../lib/crossrestore.js';
  import Spinner from '../../components/ui/Spinner.svelte';
  import CircleAlert from 'lucide-svelte/icons/circle-alert';
  import Cloud from 'lucide-svelte/icons/cloud';
  import History from 'lucide-svelte/icons/history';
  import Lock from 'lucide-svelte/icons/lock';
  import CrossRestoreDialog from './CrossRestoreDialog.svelte';

  export let game;
  export let runner;
  export let onRestored = () => {};

  let group = null;
  let loading = true;
  let failed = false;
  let error = '';

  let expandedId = '';
  let versions = [];
  let versionsLoading = false;
  let versionsFailed = false;
  let versionsError = '';

  let restoreTarget = null;

  $: members = group?.state === 'ok' ? sortMembers(group.members) : [];

  onMount(() => {
    load();
  });

  async function load() {
    loading = true;
    failed = false;
    error = '';
    expandedId = '';
    try {
      group = await api.get(`/api/cloud/game-groups/${game.id}`);
    } catch (e) {
      failed = true;
      error = e.message;
      group = null;
    } finally {
      loading = false;
    }
  }

  async function toggleVersions(member) {
    if (expandedId === member.gameId) {
      expandedId = '';
      return;
    }
    expandedId = member.gameId;
    versions = [];
    versionsFailed = false;
    versionsError = '';
    versionsLoading = true;
    try {
      const result = await api.get(`/api/cloud/game-groups/${game.id}/members/${encodeURIComponent(member.gameId)}/versions`);
      // Another row may have been opened while this request was in flight.
      if (expandedId === member.gameId) versions = result.versions ?? [];
    } catch (e) {
      if (expandedId === member.gameId) {
        versionsFailed = true;
        versionsError = e.message;
      }
    } finally {
      if (expandedId === member.gameId) versionsLoading = false;
    }
  }

  function retryVersions(member) {
    expandedId = '';
    toggleVersions(member);
  }

  function openRestore(member, version) {
    restoreTarget = { member, version };
  }

  function closeRestore() {
    restoreTarget = null;
  }

  function restored() {
    restoreTarget = null;
    onRestored?.();
  }

  function timestamp(value) {
    if (!value) return '-';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
  }
</script>

{#if loading}
  <section class="others-section">
    <div class="section-head">
      <div class="section-title"><Cloud size={16} /><h4>{$t('game.cloud.others.title')}</h4></div>
    </div>
    <p class="quiet"><Spinner size={13} /> {$t('game.cloud.others.loading')}</p>
  </section>
{:else if failed}
  <section class="others-section">
    <div class="section-head">
      <div class="section-title"><Cloud size={16} /><h4>{$t('game.cloud.others.title')}</h4></div>
    </div>
    <div class="failed">
      <p><CircleAlert size={14} /> {$t('game.cloud.others.unavailable')}</p>
      <p class="error-detail">{trError(error)}</p>
      <button class="btn small" on:click={load}>{$t('game.cloud.others.retry')}</button>
    </div>
  </section>
{:else if group?.state === 'disabled'}
  <!-- Cloud sync is not configured; the Cloud tab already says so above. -->
{:else}
  <section class="others-section">
    <div class="section-head">
      <div class="section-title"><Cloud size={16} /><h4>{$t('game.cloud.others.title')}</h4></div>
      {#if group?.state === 'ok' && members.length > 0}<span class="badge offline">{members.length}</span>{/if}
    </div>

    {#if group?.state === 'legacy'}
      <p class="quiet">{$t('game.cloud.others.legacy')}</p>
    {:else if group?.state === 'offline' || group?.state === 'error'}
      <div class="failed">
        <p><CircleAlert size={14} /> {$t('game.cloud.others.unavailable')}</p>
        <button class="btn small" on:click={load}>{$t('game.cloud.others.retry')}</button>
      </div>
    {:else if group?.state === 'not_grouped' || members.length === 0}
      <p class="quiet">{$t('game.cloud.others.empty')}</p>
    {:else}
      <div class="member-list">
        {#each members as member (member.gameId)}
          <article class="member">
            <div class="member-head">
              <div class="member-id">
                <strong title={memberTitle(member)}>{memberTitle(member)}</strong>
                <span class="badge offline">{platformLabel(member.platform) || '-'}</span>
                {#if member.retired}<span class="badge offline">{$t('cloud.gone.memberRetired')}</span>{/if}
              </div>
              <div class="member-meta">
                <span>{$t('game.cloud.others.last_version')}: {timestamp(member.lastVersionAt)}</span>
                <span>{$t('game.cloud.others.version_count', { n: member.snapshotCount ?? 0 })}</span>
                {#if (member.conflictCount ?? 0) > 0}
                  <span class="badge warn">{$t('game.cloud.others.conflict_count', { n: member.conflictCount })}</span>
                {/if}
                <button class="btn small" aria-expanded={expandedId === member.gameId} on:click={() => toggleVersions(member)}>
                  <History size={13} />{expandedId === member.gameId ? $t('game.cloud.others.hide_versions') : $t('game.cloud.others.view_versions')}
                </button>
              </div>
            </div>

            {#if expandedId === member.gameId}
              <div class="versions">
                {#if versionsLoading}
                  <p class="quiet inline"><Spinner size={13} /> {$t('game.cloud.others.versions_loading')}</p>
                {:else if versionsFailed}
                  <div class="failed inline">
                    <p>{$t('game.cloud.others.versions_failed', { error: trError(versionsError) })}</p>
                    <button class="btn small" on:click={() => retryVersions(member)}>{$t('game.cloud.others.retry')}</button>
                  </div>
                {:else if versions.length === 0}
                  <p class="quiet inline">{$t('game.cloud.others.no_versions')}</p>
                {:else}
                  <div class="version-list">
                    {#each versions as version (version.id)}
                      <div class="version-row">
                        <span>{timestamp(version.createdAt || version.receivedAt)}</span>
                        <span class="device">
                          {version.deviceName || $t('game.cloud.unknown_device')}
                          {#if version.platform}<span class="device-platform">· {platformLabel(version.platform)}</span>{/if}
                          {#if version.isHead}<span class="badge online">{$t('game.cloud.native.authoritative')}</span>{/if}
                          {#if version.locked}
                            <Lock size={13} title={$t('cloud.locked')} aria-label={$t('cloud.locked')} />
                          {/if}
                        </span>
                        <button class="btn small primary" on:click={() => openRestore(member, version)}>
                          {$t('game.cloud.others.restore_to_local')}
                        </button>
                      </div>
                    {/each}
                  </div>
                {/if}
              </div>
            {/if}
          </article>
        {/each}
      </div>
    {/if}
  </section>
{/if}

{#if restoreTarget}
  <CrossRestoreDialog
    {game}
    {runner}
    member={restoreTarget.member}
    snapshot={restoreTarget.version}
    onClose={closeRestore}
    onDone={restored}
  />
{/if}

<style>
  .others-section {
    border-top: 1px solid var(--border);
    margin-top: 14px;
    padding-top: 14px;
  }
  .section-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 7px;
  }
  .section-title {
    display: flex;
    gap: 7px;
    align-items: center;
    color: var(--text-dim);
  }
  .section-title h4 {
    font-size: 0.86rem;
    font-weight: 650;
  }
  .quiet {
    font-size: 0.8rem;
    color: var(--text-faint);
    display: flex;
    align-items: center;
    gap: 7px;
  }
  .quiet.inline {
    padding: 8px 0;
  }
  .failed {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    font-size: 0.8rem;
    color: var(--text-dim);
  }
  .failed p {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .failed :global(svg) {
    color: var(--warn);
    flex-shrink: 0;
  }
  .failed.inline {
    padding: 8px 0;
  }
  .error-detail {
    color: var(--text-faint);
    font-size: 0.76rem;
    width: 100%;
  }
  .member-list {
    border-bottom: 1px solid var(--border);
  }
  .member {
    border-top: 1px solid var(--border);
  }
  .member:first-child {
    border-top: none;
  }
  .member-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 0;
    flex-wrap: wrap;
  }
  .member-id {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .member-id strong {
    font-size: 0.86rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .member-meta {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    font-size: 0.76rem;
    color: var(--text-faint);
  }
  .versions {
    border-left: 2px solid var(--border);
    margin: 0 0 9px 4px;
    padding-left: 12px;
  }
  .version-list {
    border-top: 1px solid var(--border);
  }
  .version-row {
    display: grid;
    grid-template-columns: minmax(140px, 1fr) minmax(120px, 1fr) auto;
    gap: 10px;
    align-items: center;
    padding: 6px 0;
    border-bottom: 1px solid var(--border);
    font-size: 0.76rem;
    color: var(--text-dim);
  }
  .version-row:last-child {
    border-bottom: none;
  }
  .device {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
  }
  .device-platform {
    color: var(--text-faint);
  }
  @media (max-width: 720px) {
    .member-head {
      align-items: flex-start;
      flex-direction: column;
      gap: 6px;
    }
    .version-row {
      grid-template-columns: minmax(0, 1fr) auto;
    }
    .version-row > :first-child {
      grid-column: 1 / -1;
      color: var(--text-faint);
    }
  }
</style>
