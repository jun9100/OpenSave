# SaveVault icon

- `savevault-icon.svg` — the full icon (rounded square, 108×108 grid). The PC
  `appicon.png` / `icon.ico`, the Android frontend logo and the game-save
  favicon are rendered from it with `scripts/render_icons.mjs`.
- `savevault-mark.svg` — the single-colour shield with the controller cut
  out, for notification and themed icons.

Colours: background `#26215C`, shield `#534AB7`, controller `#EEEDFE`.
The Android launcher icon is an adaptive icon built from the same paths
(`android/app/src/main/res/mipmap-anydpi/ic_launcher.xml`), scaled into
the 66 dp safe zone.

## Rendering

```sh
npm install --no-save @resvg/resvg-js@2.6.2
node branding/render_icons.mjs
python3 -c "from PIL import Image; s=[16,24,32,48,64,128,256]; Image.open('branding/out/icon-256.png').save('cmd/opensave-app/build/windows/icon.ico', sizes=[(n,n) for n in s])"
```

`branding/out/` is scratch output and is not committed.
