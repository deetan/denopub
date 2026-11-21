import { log } from './log';

// Tizen globals (absent in desktop Chrome).
declare const tizen: any;
declare const webapis: any;

export const KEY = {
  LEFT: 37,
  UP: 38,
  RIGHT: 39,
  DOWN: 40,
  OK: 13,
  BACK: 10009,
  ESC: 27,
  BACKSPACE: 8,
  PLAYPAUSE: 10252,
  PLAY: 415,
  PAUSE: 19,
  STOP: 413,
  FF: 417,
  RW: 412,
  NEXT: 10233,
  PREV: 10232,
  SPACE: 32,
  /** Tizen IME (on-screen keyboard) Done / Cancel, sent to the focused input. */
  IME_DONE: 65376,
  IME_CANCEL: 65385,
};

export const isTizen = typeof (window as any).tizen !== 'undefined';

/** Back on the One Remote; Escape / Backspace stand in for it in desktop Chrome. */
export function isBack(code: number): boolean {
  return code === KEY.BACK || code === KEY.ESC || code === KEY.BACKSPACE;
}

/** Arrows, OK and Back arrive unregistered; media keys need registerKey. */
export function registerKeys(): void {
  if (!isTizen) return;
  ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind',
    'MediaTrackNext', 'MediaTrackPrevious'].forEach((k) => {
    try {
      tizen.tvinputdevice.registerKey(k);
    } catch (e) {
      log('registerKey failed', k, (e as Error).message);
    }
  });
}

export function exitApp(): void {
  log('exit');
  try {
    tizen.application.getCurrentApplication().exit();
  } catch (e) {
    window.close();
  }
}

/** The TV's own screensaver must not come up over a playing video. */
export function setScreenSaver(on: boolean): void {
  try {
    const ac = webapis.appcommon;
    ac.setScreenSaver(on ? ac.AppCommonScreenSaverState.SCREEN_SAVER_ON : ac.AppCommonScreenSaverState.SCREEN_SAVER_OFF);
  } catch (e) {
    if (isTizen) log('setScreenSaver', on, 'failed', (e as Error).message);
  }
}

export function platformInfo(): string {
  let v = '';
  try {
    v = tizen.systeminfo.getCapability('http://tizen.org/feature/platform.version');
  } catch (e) {
    /* desktop */
  }
  return (v ? 'Tizen ' + v : 'desktop') + ' · ' + window.innerWidth + 'x' + window.innerHeight;
}
