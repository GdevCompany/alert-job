// Полный снимок страницы YouDo / SmartCaptcha (Playwright page.evaluate / CDP)
() => {
  const vis = (el) => el && el.offsetParent !== null;
  const trim = (s, n) => (s || '').replace(/\s+/g, ' ').trim().slice(0, n);
  const login = document.querySelector('input[name="login"]');
  const code = document.querySelector('input[name="code"]');
  const token = document.querySelector('input[name="smart-token"], input[name="smart_token"]');
  const tokenVal = token && token.value ? token.value : '';
  let tokenApi = '';
  let smartCaptchaKeys = [];
  try {
    if (window.smartCaptcha) {
      smartCaptchaKeys = Object.keys(window.smartCaptcha);
      if (typeof window.smartCaptcha.getResponse === 'function') {
        tokenApi = window.smartCaptcha.getResponse() || '';
      }
    }
  } catch (e) {
    smartCaptchaKeys = ['error:' + e.message];
  }
  const buttons = [...document.querySelectorAll('button')].map((b) => ({
    text: trim(b.textContent, 80),
    visible: vis(b),
    disabled: b.disabled,
    busy: b.getAttribute('aria-busy') === 'true',
    hasSpinner: !!b.querySelector('svg, [class*="Loader"], [class*="loader"], [class*="Spinner"], [class*="spinner"]'),
    testId: b.getAttribute('data-test') || b.getAttribute('data-testid'),
  }));
  const inputs = [...document.querySelectorAll('input')].map((i) => ({
    name: i.name,
    type: i.type,
    visible: vis(i),
    valueLen: (i.value || '').length,
    value: i.type === 'hidden' || (i.name && i.name.includes('smart')) ? i.value : (i.value || '').slice(0, 120),
    disabled: i.disabled,
  }));
  const iframes = [...document.querySelectorAll('iframe')].map((f) => ({
    src: (f.src || '').slice(0, 500),
    testId: f.getAttribute('data-testid'),
    title: f.title,
    visible: vis(f),
    w: f.getBoundingClientRect().width,
    h: f.getBoundingClientRect().height,
  }));
  const dialog = login
    ? login.closest('[role="dialog"], [class*="Modal"], [class*="modal"]')
    : document.querySelector('[role="dialog"]');
  const dialogText = dialog ? trim(dialog.innerText, 4000) : trim(document.body.innerText, 2000);
  const captchaShell = !!document.querySelector('[data-sentry-component="SmartCaptcha"]');
  const overlays = [...document.querySelectorAll('.SmartCaptcha-Overlay, [class*="SmartCaptcha"], [data-sitekey]')].map((el) => ({
    tag: el.tagName,
    sitekey: el.getAttribute('data-sitekey'),
    class: (el.className || '').toString().slice(0, 120),
    visible: vis(el),
  }));
  const errors = [...document.querySelectorAll('[class*="error"], [class*="Error"], [role="alert"]')]
    .filter(vis)
    .map((el) => trim(el.textContent, 200));
  return {
    url: location.href,
    title: document.title,
    emailVisible: vis(login),
    loginValue: login ? login.value : null,
    codeInDom: !!code,
    codeVisible: vis(code),
    codeValueLen: code && code.value ? code.value.length : 0,
    smartToken: tokenVal,
    smartTokenLen: tokenVal.length,
    smartCaptchaApiToken: tokenApi,
    smartCaptchaApiTokenLen: tokenApi.length,
    smartCaptchaGlobalKeys: smartCaptchaKeys,
    captchaShell,
    sentrySmartCaptcha: captchaShell,
    buttons,
    inputs,
    iframes,
    overlays,
    errors,
    dialogText,
    cookieLen: document.cookie ? document.cookie.length : 0,
    readyState: document.readyState,
  };
};
