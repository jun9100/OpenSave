// Pausing syncing, from the app: the wording, and the calls. The state is the
// daemon's (see internal/syncpause); the app shows it and asks for changes.
import { api } from './api.js';
import { toast } from './stores.js';
import { tt, trError } from './i18n.js';

/** The lengths offered wherever pausing is, worded to follow "Pause
 *  syncing". null is until resumed. */
export const PAUSE_CHOICES = [
  {
    minutes: 15,
    get label() {
      return tt('pause.choice.fifteenMinutes');
    }
  },
  {
    minutes: 60,
    get label() {
      return tt('pause.choice.oneHour');
    }
  },
  {
    minutes: 180,
    get label() {
      return tt('pause.choice.threeHours');
    }
  },
  {
    minutes: null,
    get label() {
      return tt('pause.choice.untilResume');
    }
  }
];

/** "for 42 more minutes", "for 1 h 05 min", "until you resume". */
export function pauseLength(pause, now = Date.now()) {
  if (!pause?.paused) return '';
  if (pause.untilRestart || !pause.endsAt) return tt('pause.length.untilResume');
  const minutes = Math.max(0, Math.ceil((pause.endsAt - now) / 60000));
  if (minutes >= 60) {
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    const hours = tt('pause.unit.hours', { n: h });
    return m
      ? tt('pause.length.hoursMinutes', { hours, minutes: tt('pause.unit.minutes', { n: m }) })
      : tt('pause.length.hours', { hours });
  }
  if (minutes <= 1) return tt('pause.length.lessThanMinute');
  return tt('pause.length.moreMinutes', { minutes });
}

/** Short form for the status bar: "42 min left", "until resumed". */
export function pauseShort(pause, now = Date.now()) {
  if (!pause?.paused) return '';
  if (pause.untilRestart || !pause.endsAt) return tt('pause.short.untilResumed');
  const minutes = Math.max(0, Math.ceil((pause.endsAt - now) / 60000));
  if (minutes >= 60) {
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    return m ? tt('pause.short.hoursMinutesLeft', { h, m }) : tt('pause.short.hoursLeft', { h });
  }
  return tt('pause.short.minutesLeft', { m: Math.max(1, minutes) });
}

export async function pauseSync(minutes) {
  try {
    await api.post('/api/sync/pause', minutes ? { minutes } : { untilRestart: true });
    toast(
      minutes ? tt('pause.toast.paused') : tt('pause.toast.pausedUntilResume'),
      'success'
    );
  } catch (e) {
    toast(trError(e.message), 'error');
  }
}

export async function resumeSync() {
  try {
    await api.post('/api/sync/resume', {});
    toast(tt('pause.toast.resumed'), 'success');
  } catch (e) {
    toast(trError(e.message), 'error');
  }
}
