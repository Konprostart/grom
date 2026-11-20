(function () {
  'use strict';

  var THEME_KEY = 'grom.theme';
  var REFERRER_KEY = 'grom.referrer';
  var MY_CODE_KEY = 'grom.myCode';
  var ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  var INVITE_BASE = 'https://sveatoken.github.io/grom/';
  var API_URL = 'https://api.github.com/repos/sveatoken/grom/releases/latest';
  var ASSET_PREFIX = 'https://github.com/sveatoken/grom/releases/download/';

  var root = document.documentElement;
  root.classList.add('js');

  /* ---------- storage (Safari private mode can throw) ---------- */

  function storageGet(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (error) {
      return null;
    }
  }

  function storageSet(key, value) {
    try {
      window.localStorage.setItem(key, value);
    } catch (error) {
      /* Storage is unavailable; the page still works for this visit. */
    }
  }

  /* ---------- theme: auto / light / dark ---------- */

  var themeButtons = document.querySelectorAll('[data-theme-choice]');

  function readTheme() {
    var stored = storageGet(THEME_KEY);
    if (stored === 'light' || stored === 'dark' || stored === 'auto') {
      return stored;
    }
    return 'auto';
  }

  function applyTheme(theme) {
    root.setAttribute('data-theme', theme);
    for (var i = 0; i < themeButtons.length; i += 1) {
      var button = themeButtons[i];
      var selected = button.getAttribute('data-theme-choice') === theme;
      button.setAttribute('aria-pressed', selected ? 'true' : 'false');
    }
  }

  applyTheme(readTheme());

  for (var t = 0; t < themeButtons.length; t += 1) {
    themeButtons[t].addEventListener('click', function (event) {
      var choice = event.currentTarget.getAttribute('data-theme-choice');
      if (choice !== 'auto' && choice !== 'light' && choice !== 'dark') {
        return;
      }
      storageSet(THEME_KEY, choice);
      applyTheme(choice);
    });
  }

  /* ---------- latest release (one fetch, no token) ---------- */

  var releaseStatus = document.getElementById('releaseStatus');
  var downloadButton = document.getElementById('downloadBtn');

  function resetReleaseStatus() {
    if (releaseStatus) {
      releaseStatus.textContent = 'Latest release';
    }
  }

  function loadLatestRelease() {
    if (!releaseStatus || !downloadButton) {
      return;
    }
    if (typeof window.fetch !== 'function' || typeof window.AbortController !== 'function') {
      resetReleaseStatus();
      return;
    }

    var controller = new AbortController();
    var timeout = window.setTimeout(function () {
      controller.abort();
    }, 6000);

    function finish() {
      window.clearTimeout(timeout);
    }

    window.fetch(API_URL, {
      method: 'GET',
      headers: { Accept: 'application/vnd.github+json' },
      signal: controller.signal,
      cache: 'no-store',
      credentials: 'omit',
      referrerPolicy: 'no-referrer'
    }).then(function (response) {
      if (!response.ok) {
        throw new Error('HTTP ' + response.status);
      }
      return response.json();
    }).then(function (release) {
      if (!release || typeof release !== 'object') {
        throw new Error('Invalid release');
      }
      var tag = typeof release.tag_name === 'string' ? release.tag_name : '';
      if (!tag) {
        throw new Error('Missing release tag');
      }
      releaseStatus.textContent = 'Latest release ' + tag;

      var assets = Array.isArray(release.assets) ? release.assets : [];
      for (var i = 0; i < assets.length; i += 1) {
        var asset = assets[i];
        if (!asset || typeof asset.name !== 'string' || typeof asset.browser_download_url !== 'string') {
          continue;
        }
        if (asset.name.indexOf('server29') === -1) {
          continue;
        }
        if (asset.name.slice(-4).toLowerCase() !== '.apk') {
          continue;
        }
        if (asset.browser_download_url.indexOf(ASSET_PREFIX) !== 0) {
          continue;
        }
        downloadButton.setAttribute('href', asset.browser_download_url);
        break;
      }
    }).catch(function () {
      resetReleaseStatus();
    }).then(finish, finish);
  }

  /* ---------- invite codes ---------- */

  function sanitizeCode(raw) {
    if (typeof raw !== 'string') {
      return '';
    }
    var candidate = raw.slice(0, 64).toUpperCase().replace(/[^0-9A-Z]/g, '');
    candidate = candidate.replace(/[IL]/g, '1').replace(/O/g, '0').replace(/U/g, 'V');
    return /^[0-9A-HJKMNP-TV-Z]{4,12}$/.test(candidate) ? candidate : '';
  }

  function isOwnCode(value) {
    return typeof value === 'string' && /^[0-9A-HJKMNP-TV-Z]{8}$/.test(value);
  }

  function randomCode(length) {
    var bytes = new Uint8Array(length);
    if (window.crypto && typeof window.crypto.getRandomValues === 'function') {
      window.crypto.getRandomValues(bytes);
    } else {
      for (var b = 0; b < length; b += 1) {
        bytes[b] = Math.floor(Math.random() * 256);
      }
    }
    var code = '';
    for (var i = 0; i < length; i += 1) {
      code += ALPHABET.charAt(bytes[i] % ALPHABET.length);
    }
    return code;
  }

  var refCard = document.getElementById('refCard');
  var refCodeElement = document.getElementById('refCode');
  var copyRefButton = document.getElementById('copyRefBtn');
  var myCodeElement = document.getElementById('myCode');
  var inviteLinkElement = document.getElementById('inviteLink');
  var copyMyButton = document.getElementById('copyMyBtn');
  var copyLinkButton = document.getElementById('copyLinkBtn');
  var shareButton = document.getElementById('shareBtn');
  var copyStatus = document.getElementById('copyStatus');

  var params = new URLSearchParams(window.location.search);
  var paramRef = sanitizeCode(params.get('ref'));
  if (paramRef) {
    storageSet(REFERRER_KEY, paramRef);
  }
  var referrer = paramRef || sanitizeCode(storageGet(REFERRER_KEY));

  if (referrer && refCard && refCodeElement) {
    refCodeElement.textContent = referrer;
    refCard.hidden = false;
  }

  var myCode = storageGet(MY_CODE_KEY);
  if (!isOwnCode(myCode)) {
    myCode = randomCode(8);
    storageSet(MY_CODE_KEY, myCode);
  }

  var inviteLink = INVITE_BASE + '?ref=' + encodeURIComponent(myCode);

  if (myCodeElement) {
    myCodeElement.textContent = myCode;
  }
  if (inviteLinkElement) {
    inviteLinkElement.textContent = inviteLink;
  }

  /* ---------- clipboard feedback ---------- */

  function announce(message) {
    if (copyStatus) {
      copyStatus.textContent = message;
    }
  }

  function flashButton(button, label, duration) {
    if (!button) {
      return;
    }
    if (button.gromResetTimer) {
      window.clearTimeout(button.gromResetTimer);
    }
    if (!button.gromDefaultLabel) {
      button.gromDefaultLabel = button.textContent;
    }
    button.textContent = label;
    button.gromResetTimer = window.setTimeout(function () {
      button.textContent = button.gromDefaultLabel;
      button.gromResetTimer = null;
    }, duration);
  }

  function selectContents(element) {
    if (!element) {
      return;
    }
    try {
      var range = document.createRange();
      range.selectNodeContents(element);
      var selection = window.getSelection();
      if (!selection) {
        return;
      }
      selection.removeAllRanges();
      selection.addRange(range);
    } catch (error) {
      /* Selecting is a best-effort fallback. */
    }
  }

  function copyValue(value, button, target, successMessage) {
    if (!value) {
      return;
    }

    function succeeded() {
      flashButton(button, 'Copied', 2000);
      announce(successMessage);
    }

    function failed() {
      flashButton(button, 'Press and hold to copy', 4000);
      announce('Clipboard unavailable. Press and hold to copy.');
      selectContents(target);
    }

    if (window.navigator.clipboard && typeof window.navigator.clipboard.writeText === 'function') {
      try {
        var write = window.navigator.clipboard.writeText(value);
        if (write && typeof write.then === 'function') {
          write.then(succeeded, failed);
        } else {
          succeeded();
        }
      } catch (error) {
        failed();
      }
    } else {
      failed();
    }
  }

  if (copyRefButton) {
    copyRefButton.addEventListener('click', function () {
      copyValue(referrer, copyRefButton, refCodeElement, 'Code copied.');
    });
  }

  if (copyMyButton) {
    copyMyButton.addEventListener('click', function () {
      copyValue(myCode, copyMyButton, myCodeElement, 'Your code was copied.');
    });
  }

  if (copyLinkButton) {
    copyLinkButton.addEventListener('click', function () {
      copyValue(inviteLink, copyLinkButton, inviteLinkElement, 'Invite link copied.');
    });
  }

  if (shareButton) {
    shareButton.addEventListener('click', function () {
      var shareData = {
        title: 'Grom',
        text: 'Join me on Grom — a local server for Android.',
        url: inviteLink
      };
      if (typeof window.navigator.share === 'function') {
        try {
          var sharing = window.navigator.share(shareData);
          if (sharing && typeof sharing.then === 'function') {
            sharing.then(function () {
              announce('Invite shared.');
            }).catch(function (error) {
              if (error && error.name === 'AbortError') {
                return;
              }
              copyValue(inviteLink, shareButton, inviteLinkElement, 'Invite link copied.');
            });
          } else {
            announce('Invite shared.');
          }
        } catch (error) {
          copyValue(inviteLink, shareButton, inviteLinkElement, 'Invite link copied.');
        }
      } else {
        copyValue(inviteLink, shareButton, inviteLinkElement, 'Invite link copied.');
      }
    });
  }

  /* ---------- footer year ---------- */

  var yearElement = document.getElementById('year');
  if (yearElement) {
    yearElement.textContent = String(new Date().getFullYear());
  }

  loadLatestRelease();
})();
