// Снимок DOM внутри iframe SmartCaptcha /advanced (challenge-type, play, rep, …)
() => {
  const vis = (el) => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    const st = getComputedStyle(el);
    return r.width > 2 && r.height > 2 && st.display !== 'none' && st.visibility !== 'hidden';
  };
  const describe = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return {
      visible: vis(el),
      tag: el.tagName,
      testId: el.getAttribute('data-testid'),
      ariaLabel: (el.getAttribute('aria-label') || '').slice(0, 120),
      name: el.getAttribute('name'),
      disabled: !!el.disabled,
      className: String(el.className || '').slice(0, 160),
      bbox: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      htmlSnippet: String(el.outerHTML || '').replace(/\s+/g, ' ').slice(0, 480),
    };
  };
  const q = (s) => document.querySelector(s);
  return {
    frameUrl: location.href,
    title: document.title,
    mode: {
      kaleidoscopeCanvas: !!q('.AdvancedCaptcha-KaleidoscopeCanvas, canvas.AdvancedCaptcha-KaleidoscopeCanvas'),
      slider: !!q('#captcha-slider'),
      audioRep: !!q("input[name='rep']"),
      audioPlay: !!q("button[data-testid='play']"),
    },
    challengeType: describe(q('button[data-testid="challenge-type"]')),
    play: describe(q('button[data-testid="play"]')),
    submit: describe(q('button[data-testid="submit"]')),
    repInput: describe(q("input[name='rep']")),
    testIdButtons: [...document.querySelectorAll('button[data-testid]')].map((b) => describe(b)),
    bodyTextSnippet: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 600),
    rootHtmlSnippet: (document.documentElement.outerHTML || '').replace(/\s+/g, ' ').slice(0, 12_000),
  };
};
