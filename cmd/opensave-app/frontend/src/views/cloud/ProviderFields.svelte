<script>
  // Where a provider without a sign-in sends its backups.
  import { native } from '../../lib/api.js';

  export let config;

  async function pickFolder() {
    const dir = await native.selectDirectory('Select backup destination folder');
    if (dir) config.url = dir;
  }
</script>

{#if config.provider === 'local'}
  <div class="field">
    <label for="cb-folder">Destination folder (e.g. a NAS mount)</label>
    <div class="path-row">
      <input id="cb-folder" bind:value={config.url} placeholder="D:\Backups\OpenSave" />
      <button class="btn" on:click={pickFolder}>Browse</button>
    </div>
  </div>
{:else if config.provider === 'webdav' || config.provider === 'eden_syncd'}
  <div class="field">
    <label for="cb-url">{config.provider === 'eden_syncd' ? 'game-save service address' : 'WebDAV URL'}</label>
    <input
      id="cb-url"
      bind:value={config.url}
      placeholder={config.provider === 'eden_syncd' ? 'https://nas.example:8788' : 'https://nas.local/dav/opensave/'}
    />
    {#if config.provider === 'eden_syncd'}
      <span class="hint">Use the game-save service address. OpenSave uses its native sync API; WebDAV is not used.</span>
    {/if}
  </div>
  <div class="two">
    {#if config.provider === 'eden_syncd'}
      <div class="field two-wide">
        <label for="cb-pass">Sync key</label>
        <input id="cb-pass" bind:value={config.password} autocomplete="off" />
        <span class="hint">The game-save sync key. It is used directly for authenticated save synchronization.</span>
      </div>
    {:else}
    <div class="field">
      <label for="cb-user">Username</label>
      <input id="cb-user" bind:value={config.username} />
    </div>
    <div class="field">
      <label for="cb-pass">Password</label>
      <input id="cb-pass" bind:value={config.password} />
    </div>
    {/if}
  </div>
{:else if config.provider === 'webhook'}
  <div class="field">
    <label for="cb-hook">Webhook URL (receives multipart POST)</label>
    <input id="cb-hook" bind:value={config.url} />
  </div>
  <div class="field">
    <label for="cb-headers">Custom headers (JSON)</label>
    <input id="cb-headers" bind:value={config.headers} placeholder={'{"Authorization": "Bearer …"}'} />
  </div>
{/if}

<style>
  .two {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
  }
  .two-wide { grid-column: 1 / -1; }
</style>
