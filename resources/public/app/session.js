// The session UI: the only browser code this repository wrote.
//
// It is a served FILE rather than an inline <script> so that the
// Content-Security-Policy can be `script-src 'self' <the Clerk instance>` with
// no 'unsafe-inline' (ADR-0005). Everything it needs to know arrives as a
// `data-` attribute on <body>, which a CSP does not police and an attacker
// cannot inject a script through.
//
// It does nothing when ClerkJS is absent — an unconfigured deployment, or a
// blocked third-party script. The server-rendered "Sign in" link stays, and
// every gated page keeps refusing: the gate is on the server, and this file
// only ever adds a way in, never a way past.
(function () {
  'use strict';

  var LANDING_URL = '/';
  var DEFAULT_RETURN_URL = '/library';

  // Clerk mints a session token that lives sixty seconds and refreshes it in
  // the background. htmx's `configRequest` hook is synchronous and cannot wait
  // for a promise, so one token is kept in hand and renewed well inside its own
  // lifetime; what a request carries is therefore always a token minted in the
  // last few seconds.
  var TOKEN_REFRESH_MS = 20000;
  var token = null;

  function config(name, fallback) {
    return document.body.getAttribute('data-' + name) || fallback;
  }

  function attachToken(event) {
    if (token) {
      event.detail.headers['Authorization'] = 'Bearer ' + token;
    }
  }

  function keepTokenFresh(clerk) {
    function renew() {
      if (!clerk.session) {
        token = null;
        return;
      }
      clerk.session.getToken().then(
        function (fresh) { token = fresh; },
        function () { token = null; }
      );
    }

    renew();
    window.setInterval(renew, TOKEN_REFRESH_MS);
    document.body.addEventListener('htmx:configRequest', attachToken);
  }

  function mountSessionNav(clerk) {
    var slot = document.getElementById('session-nav');
    if (!slot || !clerk.user) {
      return;
    }

    // Signed in: the server-rendered "Sign in" link is replaced by Clerk's own
    // account menu, which is also where signing out happens.
    slot.textContent = '';
    clerk.mountUserButton(slot, { afterSignOutUrl: LANDING_URL });
  }

  function mountSignIn(clerk) {
    var slot = document.getElementById('sign-in');
    if (!slot) {
      return;
    }

    var returnUrl = config('return-to', DEFAULT_RETURN_URL);
    if (clerk.user) {
      window.location.replace(returnUrl);
      return;
    }

    slot.textContent = '';
    clerk.mountSignIn(slot, {
      fallbackRedirectUrl: returnUrl,
      signUpFallbackRedirectUrl: returnUrl
    });

    // Belt as well as braces. The prop that names the post-sign-in destination
    // has been renamed between ClerkJS majors, and landing on the wrong page is
    // the one failure a reader would actually notice; this listener depends on
    // no prop name at all.
    clerk.addListener(function (payload) {
      if (payload.user) {
        window.location.replace(returnUrl);
      }
    });
  }

  window.addEventListener('load', function () {
    if (!window.Clerk) {
      return;
    }

    // Since clerk-js 6 the UI components live in a second bundle, and
    // `mountSignIn` throws "Clerk was not loaded with Ui components" unless its
    // constructor is handed in here. The constructor is absent only if that
    // script failed to load, in which case load() still gives a working session
    // (token refresh, the gate) with no mountable components.
    var options = window.__internal_ClerkUICtor
      ? { ui: { ClerkUI: window.__internal_ClerkUICtor } }
      : {};

    window.Clerk.load(options).then(function () {
      mountSessionNav(window.Clerk);
      mountSignIn(window.Clerk);
      keepTokenFresh(window.Clerk);
    });
  });
})();
