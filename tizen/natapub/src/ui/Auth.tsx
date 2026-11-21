import { useEffect, useState } from 'preact/hooks';
import { afterLogin, pollDeviceToken, requestDeviceCode } from '../api/kinopub';
import { log } from '../log';
import { VERSION } from '../version';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Device-code login (AuthScreen.kt): the TV shows a code, the owner enters it at kino.pub/device. */
export function Auth({ onDone }: { onDone: () => void }) {
  const [code, setCode] = useState<string | null>(null);
  const [uri, setUri] = useState('kino.pub/device');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    (async () => {
      while (alive) {
        try {
          const dc = await requestDeviceCode();
          if (!alive) return;
          setCode(dc.user_code);
          setUri(dc.verification_uri.replace(/^https?:\/\//, ''));
          setError(null);
          log('auth: device code', dc.user_code, 'at', dc.verification_uri, 'expires in', dc.expires_in, 's');
          const deadline = Date.now() + (dc.expires_in || 300) * 1000;
          while (alive && Date.now() < deadline) {
            await sleep(Math.max(5, dc.interval || 5) * 1000);
            if (!alive) return;
            let ok = false;
            try {
              ok = await pollDeviceToken(dc.code);
            } catch (e) {
              log('auth: poll', (e as Error).message);
              if ((e as Error).message.indexOf('expired') >= 0) break;
            }
            if (ok) {
              log('auth: logged in');
              await afterLogin(VERSION);
              if (alive) onDone();
              return;
            }
          }
          // Code expired: loop and fetch a fresh one.
        } catch (e) {
          setError((e as Error).message);
          log('auth: error', (e as Error).message);
          await sleep(5000);
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  return (
    <div class="screen">
      <div class="center-msg">
        <div class="t-headline">Войти в kino.pub</div>
        <div class="t-title muted" style={{ marginTop: '20px' }}>Откройте {uri} и введите код</div>
        <div class="t-display" style={{ fontSize: '72px', letterSpacing: '12px', color: 'var(--accent)', marginTop: '20px', paddingLeft: '12px' }}>
          {code || '······'}
        </div>
        {error ? <div class="t-body" style={{ color: 'var(--badge)', marginTop: '20px' }}>Ошибка: {error}</div> : null}
      </div>
    </div>
  );
}
