// Every test runs in English, whatever the language of the machine running
// it: the app follows the system language when nothing is chosen, and the
// tests assert English wording. Tests of the language choice itself pass
// their own storage and navigator language (lib/i18n.test.js).
import { locale } from './lib/i18n.js';

locale.set('en');
