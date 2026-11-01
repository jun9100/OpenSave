<script>
  import { slidingIndicator } from '../lib/motion.js';
  import { peers, discoveredPeers, wanRoom, appUpdate, toast, askConfirm } from '../lib/stores.js';
  import { api, native } from '../lib/api.js';
  import { t, tt, trError } from '../lib/i18n.js';
  import InternetSync from './InternetSync.svelte';
  import ProtectionBadge from '../components/ProtectionBadge.svelte';
  import { protectionState } from '../lib/protection.js';
  import DeviceIcon from '../components/DeviceIcon.svelte';
  import Globe from 'lucide-svelte/icons/globe';
  import Network from 'lucide-svelte/icons/network';
  import Download from 'lucide-svelte/icons/download';

  export let params = {};

  let manualIp = '';
  let manualPort = 8383;
  let busy = false;
  // Which "add a device" method is shown. Deep-links can request the
  // internet tab; otherwise default to the local network.
  let connectTab = params.tab === 'internet' ? 'wan' : 'lan';

  $: pairedList = Object.values($peers).sort((a, b) => a.name.localeCompare(b.name));
  $: pairedIds = new Set(pairedList.map((p) => p.id));
  $: lanDiscovered = $discoveredPeers.filter((d) => !d.isWan && !pairedIds.has(d.id));

  async function run(fn, okMsg) {
    if (busy) return;
    busy = true;
    try {
      await fn();
      if (okMsg) toast(okMsg, 'success');
    } catch (e) {
      toast(trError(e.message), 'error');
    } finally {
      busy = false;
    }
  }

  const pairDiscovered = (d) =>
    run(() => api.post('/api/peers/pair', { address: d.address, port: d.port }), tt('devices.pairingSentTo', { name: d.deviceName }));
  const pairManual = () =>
    run(() => api.post('/api/peers/pair', { address: manualIp, port: Number(manualPort) }), tt('devices.pairingSent'));
  const unpair = async (peer) => {
    if (!(await askConfirm(tt('devices.unpairConfirm', { name: peer.name }), { title: tt('devices.unpairTitle'), confirmText: tt('devices.unpair'), danger: true }))) return;
    run(() => api.del(`/api/peers/${peer.id}`), tt('devices.unpaired', { name: peer.name }));
  };

  // The only repair for a pairing with no encryption key is to make it again,
  // and this button can only do the first half of that. Saying "Pair again to
  // encrypt" on a control that just unpairs would leave someone believing they
  // had fixed it, with two devices that no longer sync at all.
  const repairPairing = async (peer) => {
    const ok = await askConfirm(tt('devices.repairConfirm', { name: peer.name }), {
      title: tt('devices.repairTitle'),
      confirmText: tt('devices.unpairNow'),
    });
    if (!ok) return;
    run(
      () => api.del(`/api/peers/${peer.id}`),
      tt('devices.repairDone', { name: peer.name })
    );
  };

  // Peer-to-peer app update: pull the newer build the peer is running and
  // install it here — no manually copying the exe between machines.
  const updateFromPeer = async (peer) => {
    const ok = await askConfirm(
      tt('devices.updateConfirm', { name: peer.name, version: peer.appVersion }),
      { title: tt('devices.updateTitle'), confirmText: tt('devices.updateRestart') }
    );
    if (!ok) return;
    const err = await native.installFromPeer(peer.id);
    if (err) toast(trError(err), 'error');
  };

  $: fmtTime = (ts) => (ts ? new Date(ts).toLocaleString() : $t('devices.never'));
  // The status is an id from the service; only the two known ones have words.
  $: statusLabel = (s) =>
    s === 'online' ? $t('devices.status.online') : s === 'offline' ? $t('devices.status.offline') : s;
</script>

<div class="head">
  <h2 class="page-title">{$t('devices.title')}</h2>
</div>

<h3 class="section">{$t('devices.paired')}</h3>
{#if pairedList.length === 0}
  <div class="empty">
    <h3>{$t('devices.noPaired')}</h3>
    <p>{$t('devices.noPairedHint')}</p>
  </div>
{:else}
  <div class="list">
    {#each pairedList as peer (peer.id)}
      <div class="card peer">
        <DeviceIcon type={peer.deviceType} />
        <div class="peer-info">
          <div class="peer-name">
            {peer.name}
            <span class="badge" class:online={peer.status === 'online'} class:offline={peer.status !== 'online'}>
              {statusLabel(peer.status)}
            </span>
          </div>
          <div class="peer-meta">
            {$t('devices.peerMeta', {
              address: peer.address === 'relay' ? $t('devices.internetRelay') : `${peer.address}:${peer.port}`,
              time: fmtTime(peer.lastSynced),
            })}
            {#if peer.appVersion}· OpenSave {peer.appVersion}{/if}
          </div>
          <!-- Per device, next to how it is reached, because that is the
               context the answer depends on. -->
          <div class="peer-protection">
            <ProtectionBadge {peer} compact />
            {#if protectionState(peer) === 'open'}
              <button class="linkish" disabled={busy} on:click={() => repairPairing(peer)}>
                {$t('devices.fixThis')}
              </button>
            {/if}
          </div>
        </div>
        {#if peer.hasNewerBuild && peer.status === 'online'}
          <button class="btn small primary" disabled={busy || !!$appUpdate} on:click={() => updateFromPeer(peer)}>
            <Download size={14} />{$t('devices.updateFromThis')}
          </button>
        {/if}
        <button class="btn small danger" disabled={busy} on:click={() => unpair(peer)}>{$t('devices.unpair')}</button>
      </div>
    {/each}
  </div>
{/if}

<h3 class="section">{$t('devices.addDevice')}</h3>
<div class="pill-tabs connect-tabs" use:slidingIndicator={{ inset: 10 }}>
  <button class:active={connectTab === 'lan'} on:click={() => (connectTab = 'lan')}><Network size={15} />{$t('devices.onThisNetwork')}</button>
  <button class:active={connectTab === 'wan'} on:click={() => (connectTab = 'wan')}>
    <Globe size={15} />{$t('devices.overInternet')}
    {#if $wanRoom?.connected}<span class="tab-dot"></span>{/if}
  </button>
</div>

{#if connectTab === 'lan'}
  <p class="quiet lan-intro">
    {$t('devices.lanIntro')}
  </p>
  <h4 class="subsection">{$t('devices.foundOnNetwork')}</h4>
  {#if lanDiscovered.length === 0}
    <p class="quiet">{$t('devices.noneFound')}</p>
  {:else}
    <div class="list">
      {#each lanDiscovered as d (d.id)}
        <div class="card peer">
          <DeviceIcon type={d.deviceType} />
          <div class="peer-info">
            <div class="peer-name">{d.deviceName}</div>
            <div class="peer-meta">{d.address}:{d.port}</div>
          </div>
          <button class="btn small primary" disabled={busy} on:click={() => pairDiscovered(d)}>{$t('devices.pair')}</button>
        </div>
      {/each}
    </div>
  {/if}

  <h4 class="subsection">{$t('devices.addByIp')}</h4>
  <div class="card manual">
    <input placeholder="192.168.1.42" bind:value={manualIp} />
    <input class="port" type="number" bind:value={manualPort} />
    <button class="btn primary" disabled={!manualIp || busy} on:click={pairManual}>{$t('devices.sendPairingRequest')}</button>
  </div>
{:else}
  <InternetSync />
{/if}

<style>
  .head {
    margin-bottom: 20px;
  }
  .section {
    margin: 22px 0 10px;
  }
  .subsection {
    font-size: 0.9rem;
    font-weight: 600;
    color: var(--text-dim);
    margin: 18px 0 8px;
  }
  .connect-tabs {
    margin-bottom: 14px;
  }
  .connect-tabs button {
    display: inline-flex;
    align-items: center;
    gap: 7px;
  }
  .tab-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--success);
  }
  .lan-intro {
    margin-bottom: 4px;
  }
  .list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .peer {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 14px 16px;
  }
  .peer-info {
    flex: 1;
    min-width: 0;
  }
  .peer-name {
    font-weight: 600;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .peer-protection {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-top: 0.35rem;
    flex-wrap: wrap;
  }
  .linkish {
    background: none;
    border: none;
    padding: 0;
    font: inherit;
    font-size: 0.78rem;
    color: var(--accent-text);
    text-decoration: underline;
    cursor: pointer;
  }
  .linkish:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .peer-meta {
    font-size: 0.78rem;
    color: var(--text-faint);
    margin-top: 2px;
  }
  .quiet {
    color: var(--text-faint);
    font-size: 0.88rem;
  }
  .manual {
    display: flex;
    gap: 10px;
    padding: 14px;
  }
  .manual input {
    padding: 8px 12px;
    background: var(--bg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--text);
    outline: none;
    flex: 1;
  }
  .manual .port {
    flex: 0 0 90px;
  }
</style>
