<script>
  // Per-game settings, edited as a form and saved together with one button.
  import { askConfirm } from '../../lib/stores.js';
  import { api, native, coverURL } from '../../lib/api.js';
  import { savePathChange } from '../../lib/savepath.js';
  import { t, segments } from '../../lib/i18n.js';
  import AppIdField from './AppIdField.svelte';
  import SyncIgnoreEditor from './SyncIgnoreEditor.svelte';
  import SaveLocations from './SaveLocations.svelte';
  import MoreInfo from '../../components/ui/MoreInfo.svelte';
  import Gamepad2 from 'lucide-svelte/icons/gamepad-2';

  export let game;
  export let runner;
  const { busy, run } = runner;

  // Taken from the game once, when the tab opens: the game updates often
  // (every sync), and re-reading it would throw away what is being typed.
  let cfg = {
    appId: game.appId ?? '',
    savePath: game.savePath ?? '',
    exePath: game.exePath ?? '',
    // The art Steam has for the App ID is not a custom cover: shown here it
    // read as one, beside a hint saying to leave the field blank for it.
    coverUrl: game.coverUrl && !game.coverUrl.includes('steamstatic.com/steam/apps/') ? game.coverUrl : '',
    autoSync: game.autoSync ?? true,
    maxSnapshots: game.maxSnapshots ?? 5,
    maxManualSnapshots: game.maxManualSnapshots ?? 0,
    syncIgnore: game.syncIgnore ?? ''
  };
  let locations = [];

  // Cover preview: a custom URL wins, else the proxied Steam art for the App
  // ID being edited.
  $: cover =
    cfg.coverUrl && !cfg.coverUrl.includes('steamstatic.com') ? cfg.coverUrl : coverURL(cfg.appId, false, '');

  // A Steam App ID is digits. Saving something else stores a value that
  // matches nothing, launches nothing and fetches no art, with no complaint
  // at any point — which reads as the features being broken rather than the
  // field being wrong. Catch it at the edit instead.
  let appIdError = '';

  async function save() {
    const appId = (cfg.appId ?? '').trim();
    if (appId !== '' && !/^\d+$/.test(appId)) {
      appIdError = $t('game.config.appIdDigits');
      return;
    }
    appIdError = '';

    // Moving tracking to a different folder is the one edit on this form that
    // changes WHICH files are the save, rather than a detail about them. The
    // old folder is left untouched, but nothing there syncs or is snapshotted
    // any more, so it is worth one confirmation.
    const savePath = savePathChange(game.savePath, cfg.savePath);
    if (savePath) {
      const ok = await askConfirm($t('game.config.moveConfirm', { name: game.name, path: savePath }), {
        title: $t('game.config.moveTitle'),
        confirmText: $t('game.config.moveAction')
      });
      if (!ok) return;
    }

    await run($t('game.config.saved'), () =>
      api.patch(`/api/games/${game.id}`, {
        appId,
        // Omitted unless it actually changed: the PATCH decodes into the
        // stored game, so leaving it out keeps the current folder. Sending an
        // empty string instead would be a real edit, and validation would
        // reject it — blocking every other change on this form.
        ...(savePath ? { savePath } : {}),
        exePath: cfg.exePath,
        coverUrl: cfg.coverUrl,
        autoSync: cfg.autoSync,
        maxSnapshots: Number(cfg.maxSnapshots),
        maxManualSnapshots: Number(cfg.maxManualSnapshots),
        syncIgnore: cfg.syncIgnore ?? ''
      })
    );
  }

  async function browseSavePath() {
    const dir = await native.selectDirectory($t('game.config.savePathDialog', { name: game.name }));
    if (dir) cfg.savePath = dir;
  }

  async function browseExe() {
    const file = await native.selectFile($t('game.config.exeDialog'));
    if (file) cfg.exePath = file;
  }
</script>

<div class="card config">
  <div class="cover">
    {#if cover}
      <img src={cover} alt="" on:error={(e) => (e.currentTarget.style.display = 'none')} />
    {:else}
      <div class="cover-fallback"><Gamepad2 size={30} strokeWidth={1.5} /></div>
    {/if}
  </div>
  <div class="fields">
    <h3>{$t('game.config.title')}</h3>
    <div class="field">
      <label for="c-savepath">{$t('game.config.savePath')}</label>
      <div class="path-row">
        <input id="c-savepath" placeholder={$t('game.config.savePathPlaceholder')} bind:value={cfg.savePath} />
        <button class="btn" on:click={browseSavePath}>{$t('game.config.browse')}</button>
      </div>
      <span class="hint">
        {$t('game.config.savePathHint')}
        <MoreInfo
          >{#each segments($t('game.config.savePathMore'), { inside: $t('game.config.savePathInside') }, ['inside']) as part}{#if part.bold}<em
                >{part.text}</em
              >{:else}{part.text}{/if}{/each}</MoreInfo
        >
      </span>
      {#if savePathChange(game.savePath, cfg.savePath)}
        <span class="hint hint-warn">
          {$t('game.config.savePathPending')}
        </span>
      {/if}
    </div>
    <AppIdField bind:value={cfg.appId} error={appIdError} />
    <div class="field">
      <label for="c-exe">{$t('game.config.exePath')}</label>
      <div class="path-row">
        <input id="c-exe" placeholder={$t('game.config.exePlaceholder')} bind:value={cfg.exePath} />
        <button class="btn" on:click={browseExe}>{$t('game.config.browse')}</button>
      </div>
      <span class="hint">
        {$t('game.config.exeHint')}
      </span>
    </div>
    <div class="field">
      <label for="c-cover">{$t('game.config.cover')}</label>
      <input id="c-cover" placeholder={$t('game.config.coverPlaceholder')} bind:value={cfg.coverUrl} />
      <span class="hint">{$t('game.config.coverHint')}</span>
    </div>
    <label class="check">
      <input type="checkbox" bind:checked={cfg.autoSync} />
      {$t('game.config.autoSync')}
    </label>
    <div class="field" style="margin-top: 12px;">
      <label for="c-max">{$t('game.config.maxAuto')}</label>
      <input id="c-max" type="number" min="0" bind:value={cfg.maxSnapshots} />
      <span class="hint">
        {#each segments($t('game.config.maxAutoHint'), { automatic: $t('game.config.maxAutoHintEm') }, ['automatic']) as part}{#if part.bold}<em
              >{part.text}</em
            >{:else}{part.text}{/if}{/each}
      </span>
    </div>
    <div class="field">
      <label for="c-max-manual">{$t('game.config.maxManual')}</label>
      <input id="c-max-manual" type="number" min="0" bind:value={cfg.maxManualSnapshots} />
      <span class="hint">
        {#each segments($t('game.config.maxManualHint'), { keepForever: $t('game.config.maxManualHintStrong') }, ['keepForever']) as part}{#if part.bold}<strong
              >{part.text}</strong
            >{:else}{part.text}{/if}{/each}
      </span>
    </div>
    <SyncIgnoreEditor {game} bind:value={cfg.syncIgnore} severalFolders={locations.length > 0} />
    <SaveLocations {game} {runner} bind:locations />
    <div class="save">
      <button class="btn primary" disabled={$busy} on:click={save}>{$t('game.config.save')}</button>
    </div>
  </div>
</div>

<style>
  .config {
    display: flex;
    gap: 20px;
    align-items: flex-start;
  }
  .cover {
    flex-shrink: 0;
    width: 160px;
    aspect-ratio: 460 / 215;
    border-radius: var(--radius);
    overflow: hidden;
    border: 1px solid var(--border);
    background: var(--bg);
  }
  .cover img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .cover-fallback {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-faint);
  }
  .fields {
    flex: 1;
    min-width: 0;
  }
  .fields h3 {
    margin-bottom: 14px;
  }
  .fields .field {
    margin-bottom: 14px;
  }
  .hint-warn {
    color: var(--warn);
  }
  .save {
    display: flex;
    justify-content: flex-end;
    margin-top: 8px;
  }
  @media (max-width: 720px) {
    .config {
      flex-direction: column;
    }
    .cover {
      width: 100%;
    }
  }
</style>
