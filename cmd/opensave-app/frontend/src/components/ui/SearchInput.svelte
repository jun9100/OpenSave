<script>
  import { t } from '../../lib/i18n.js';

  export let value = '';
  /** Left unset, it says "Filter…" in the chosen language. */
  export let placeholder = null;

  $: shown = placeholder ?? $t('ui.filter');
</script>

<!-- data-find: what Ctrl+F focuses (see components/Shortcuts.svelte). -->
<input class="search" data-find placeholder={shown} aria-label={shown} bind:value />

<style>
  .search {
    flex: 1;
    min-width: 200px;
    padding: 9px 13px;
    background: var(--bg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--text);
    outline: none;
  }
</style>
