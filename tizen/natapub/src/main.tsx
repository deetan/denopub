import { render } from 'preact';
import { initKeys } from './keys';
import { installErrorHooks, log } from './log';
import { platformInfo, registerKeys } from './platform';
import { initDeepLinks } from './preview';
import { App } from './ui/App';
import './ui/app.css';
import { VERSION } from './version';

installErrorHooks();

/** #app is laid out in dp (960×540) and zoomed to the window: ×2 on the TV's 1920×1080 canvas. */
function fit(): void {
  const el = document.getElementById('app') as HTMLElement;
  const z = Math.min(window.innerWidth / 960, window.innerHeight / 540);
  (el.style as any).zoom = String(z);
}

async function boot(): Promise<void> {
  if (import.meta.env.DEV && /[?&]fixture/.test(location.search)) {
    const f = await import('./dev/fixture');
    f.install();
  }
  log('natapub', VERSION, 'start ·', platformInfo(), '·', navigator.userAgent);
  fit();
  window.addEventListener('resize', fit);
  registerKeys();
  initKeys();
  initDeepLinks(); // before App subscribes: a cold start's link waits for it
  render(<App />, document.getElementById('app') as HTMLElement);
  // One-time diagnostics for the TV (no screen access from the Mac): zoom and the bundled font.
  setTimeout(() => {
    const app = document.getElementById('app') as HTMLElement;
    const fonts = (document as any).fonts;
    log('layout: zoom', (app.style as any).zoom, 'rect', Math.round(app.getBoundingClientRect().width),
      'roboto', fonts ? fonts.check('500 15px Roboto', 'Жж') : 'n/a');
  }, 3000);
}

boot();
