<script>
  // Naming a folder picked to track. "Track folder" opens the system's folder
  // picker straight away (Home.svelte), and this is what follows it — or a
  // folder dropped onto the window: the folder, a name to confirm, and a way
  // to pick another folder or a single file instead. The name is suggested
  // from the path (GET /api/suggest-name), which knows a Switch game by the
  // name its emulator shows. Fires `close` on Cancel and after tracking, which
  // also opens the new game's page.
  import { createEventDispatcher, onMount, tick } from 'svelte';
  import FolderPlus from 'lucide-svelte/icons/folder-plus';
  import Modal from '../../components/ui/Modal.svelte';
  import ModalFoot from '../../components/ui/ModalFoot.svelte';
  import { navigate, toast, askConfirm } from '../../lib/stores.js';
  import { api, native } from '../../lib/api.js';
  import { folderName } from '../../lib/filedrop.js';
  import { t, trError } from '../../lib/i18n.js';
  import { overlapOf } from '../../lib/overlap.js';

  const dispatch = createEventDispatcher();

  /** The folder or file picked; '' where there is no picker (a browser). */
  export let path = '';

  let name = '';
  let typed = false; // once typed in, a late suggestion must not replace it
  let adding = false;
  let saveType = 'pc';
  let nameInput;
  const canPick = native.isWails();

  let suggestedFor = null;
  $: if (path !== suggestedFor) suggest(path);
  async function suggest(p) {
    suggestedFor = p;
    if (!p) return;
    let guess = folderName(p);
    try {
      guess = (await api.get(`/api/suggest-name?path=${encodeURIComponent(p)}`)).name || guess;
    } catch {
      // The folder's own name will do.
    }
    if (p !== path || typed) return;
    name = guess;
    await tick();
    nameInput?.select();
  }

  onMount(() => nameInput?.focus());

  async function pickFolder() {
    const dir = await native.selectDirectory($t('home.chooseFolderDialog'));
    if (dir) {
      typed = false;
      path = dir;
    }
  }

  async function pickFile() {
    const file = await native.selectFile($t('home.addGame.chooseFile'));
    if (file) {
      typed = false;
      path = file;
    }
  }

  async function add() {
    if (!name.trim() || !path || adding) return;
    adding = true;
    try {
      const body = { name: name.trim(), savePath: path, saveType };
      let game;
      try {
        game = await api.post('/api/games', body);
      } catch (e) {
        // Inside, or around, another game's folder: both would back up the
        // same files on every save. Say so, and add only if asked to.
        const overlap = overlapOf(e.message);
        if (!overlap) throw e;
        const ok = await askConfirm(
          $t('home.addGame.overlapMessage', { path, name: overlap.name, other: overlap.path }),
          { title: $t('home.addGame.overlapTitle'), confirmText: $t('home.addGame.overlapConfirm'), danger: true }
        );
        if (!ok) return;
        game = await api.post('/api/games', { ...body, allowOverlap: true });
      }
      toast($t('common.nowTracking', { name: game.name ?? name }), 'success');
      dispatch('close');
      navigate('game', { gameId: game.id });
    } catch (e) {
      toast(trError(e.message), 'error');
    } finally {
      adding = false;
    }
  }
</script>

<Modal title={$t('home.addGame.title')} icon={FolderPlus} width={560} height="auto" onClose={() => dispatch('close')}>
  <div class="body">
    <div class="field">
      <span class="label">{$t('home.addGame.savePath')}</span>
      {#if canPick}
        <div class="picked" title={path}><bdi>{path}</bdi></div>
        <div class="alt">
          <button class="linklike" on:click={pickFolder}>{$t('home.addGame.changeFolder')}</button>
          <button class="linklike" on:click={pickFile}>{$t('home.addGame.trackFileInstead')}</button>
        </div>
      {:else}
        <input id="g-path" placeholder="C:\Users\you\AppData\…" bind:value={path} />
      {/if}
    </div>
    <div class="field">
      <label for="g-save-type">{$t('home.addGame.saveType')}</label>
      <select id="g-save-type" bind:value={saveType}>
        <option value="pc">{$t('home.saveType.pcGame')}</option>
        <option value="switch">Switch</option>
        <option value="emulator-eden">Eden</option>
        <option value="emulator-yuzu">Yuzu / Suyu</option>
        <option value="emulator-ryujinx">Ryujinx</option>
        <option value="emulator-generic">{$t('home.saveType.otherEmulator')}</option>
      </select>
      <span class="hint">{$t('home.addGame.saveTypeHint')}</span>
    </div>
    <div class="field">
      <label for="g-name">{$t('home.addGame.name')}</label>
      <input
        id="g-name"
        bind:this={nameInput}
        placeholder={$t('home.addGame.namePlaceholder')}
        bind:value={name}
        on:input={() => (typed = true)}
        on:keydown={(e) => e.key === 'Enter' && add()}
      />
      <span class="hint">{$t('home.addGame.nameHint')}</span>
    </div>
  </div>
  <ModalFoot>
    <span />
    <div class="actions">
      <button class="btn" on:click={() => dispatch('close')}>{$t('common.cancel')}</button>
      <button class="btn primary" disabled={!name.trim() || !path || adding} on:click={add}>
        {adding ? $t('home.addGame.adding') : $t('home.addGame.startTracking')}
      </button>
    </div>
  </ModalFoot>
</Modal>

<style>
  .body {
    padding: 18px 22px 20px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .label,
  label {
    font-size: 0.82rem;
    color: var(--text-dim);
  }
  .picked {
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: 0.8rem;
    color: var(--text);
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 8px 10px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    direction: rtl; /* long paths keep their end — the part that names the game */
    text-align: left;
  }
  .alt {
    display: flex;
    gap: 16px;
  }
  .linklike {
    background: none;
    border: none;
    padding: 0;
    font: inherit;
    font-size: 0.8rem;
    color: var(--text-dim);
    text-decoration: underline;
    cursor: pointer;
  }
  .linklike:hover {
    color: var(--text);
  }
  .hint {
    margin: 0;
  }
  .actions {
    display: flex;
    gap: 8px;
  }
</style>
