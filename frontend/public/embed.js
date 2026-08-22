(function () {
  'use strict';

  // Must run synchronously at top-level, before any async work -- document.currentScript is
  // only valid while this script is the one actively executing.
  var scriptEl = document.currentScript;
  if (!scriptEl) return;

  var department = scriptEl.getAttribute('data-department') || '';
  var apiBase = scriptEl.getAttribute('data-api-base') || new URL(scriptEl.src, window.location.href).origin;

  var embedUrl = apiBase + '/embed/chat' + (department ? '?department=' + encodeURIComponent(department) : '');

  var open = false;
  var iframe = null;

  var button = document.createElement('button');
  button.type = 'button';
  button.setAttribute('aria-label', 'Open chat');
  applyStyles(button, {
    position: 'fixed',
    right: '24px',
    bottom: '24px',
    width: '56px',
    height: '56px',
    borderRadius: '50%',
    border: 'none',
    background: '#1a3a5c',
    color: '#fff',
    cursor: 'pointer',
    boxShadow: '0 4px 16px rgba(0,0,0,0.3)',
    zIndex: '2147483000',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '0'
  });
  button.innerHTML = chatIconSvg();

  var panel = document.createElement('div');
  applyStyles(panel, {
    position: 'fixed',
    right: '24px',
    bottom: '92px',
    width: 'min(380px, calc(100vw - 48px))',
    height: 'min(560px, calc(100vh - 140px))',
    borderRadius: '16px',
    boxShadow: '0 8px 32px rgba(0,0,0,0.3)',
    overflow: 'hidden',
    zIndex: '2147483000',
    display: 'none',
    background: '#fff'
  });

  function ensureIframe() {
    if (iframe) return;
    iframe = document.createElement('iframe');
    iframe.src = embedUrl;
    iframe.title = 'Chat assistant';
    applyStyles(iframe, { width: '100%', height: '100%', border: 'none' });
    panel.appendChild(iframe);
  }

  function toggle() {
    open = !open;
    if (open) {
      ensureIframe();
      panel.style.display = 'block';
      button.innerHTML = closeIconSvg();
      button.setAttribute('aria-label', 'Close chat');
    } else {
      panel.style.display = 'none';
      button.innerHTML = chatIconSvg();
      button.setAttribute('aria-label', 'Open chat');
    }
  }

  button.addEventListener('click', toggle);

  function applyStyles(el, styles) {
    for (var key in styles) {
      if (Object.prototype.hasOwnProperty.call(styles, key)) {
        el.style[key] = styles[key];
      }
    }
  }

  function chatIconSvg() {
    return (
      '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">' +
      '<path d="M4 4h16v12H7l-3 3V4z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>' +
      '</svg>'
    );
  }

  function closeIconSvg() {
    return (
      '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">' +
      '<path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>' +
      '</svg>'
    );
  }

  function mount() {
    document.body.appendChild(panel);
    document.body.appendChild(button);
  }

  if (document.body) {
    mount();
  } else {
    document.addEventListener('DOMContentLoaded', mount);
  }
})();
