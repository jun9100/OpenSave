<script>
  // A paired or discovered device, by kind: a handheld or a computer.
  import Gamepad2 from 'lucide-svelte/icons/gamepad-2';
  import Monitor from 'lucide-svelte/icons/monitor';
  import { isHandheld } from '../lib/devices.js';
  import { t } from '../lib/i18n.js';

  /** The device's reported type. */
  export let type = '';

  $: handheld = isHandheld(type);
</script>

<span class="device-icon" title={handheld ? $t('ui.device.handheld') : $t('ui.device.computer')}>
  <svelte:component this={handheld ? Gamepad2 : Monitor} size={19} />
</span>

<style>
  .device-icon {
    flex-shrink: 0;
    width: 38px;
    height: 38px;
    border-radius: 10px;
    display: grid;
    place-items: center;
    background: var(--bg-hover);
    color: var(--text-dim);
  }
</style>
