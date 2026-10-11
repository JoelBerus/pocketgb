/* PocketGB · landing. Sin dependencias ni peticiones externas.
   - Idioma: ?lang= > localStorage > idioma del navegador > español (el HTML ya viene en español).
   - Tema: sigue al sistema hasta que se pulsa el botón; entonces se recuerda.
   - Pestañas accesibles (flechas, Inicio, Fin), aparición al entrar en pantalla y botón de copiar. */
(function () {
  "use strict";

  var root = document.documentElement;
  root.classList.remove("no-js");
  root.classList.add("js");

  var EN = window.POCKETGB_I18N_EN || {};
  var store = {
    get: function (k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { window.localStorage.setItem(k, v); } catch (e) { /* modo privado */ } }
  };

  /* ---------- Idioma ---------- */
  var LANG_KEY = "pocketgb.lang";
  var original = { html: new Map(), attr: new Map() };

  function pickLang() {
    var q = new URLSearchParams(window.location.search).get("lang");
    if (q === "es" || q === "en") return q;
    var saved = store.get(LANG_KEY);
    if (saved === "es" || saved === "en") return saved;
    var nav = (navigator.language || "es").toLowerCase();
    return nav.indexOf("en") === 0 ? "en" : "es";
  }

  function parseAttr(spec) {
    return spec.split(";").map(function (pair) {
      var i = pair.indexOf(":");
      return [pair.slice(0, i).trim(), pair.slice(i + 1).trim()];
    }).filter(function (p) { return p[0] && p[1]; });
  }

  function remember() {
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      original.html.set(el, el.innerHTML);
    });
    document.querySelectorAll("[data-i18n-attr]").forEach(function (el) {
      var saved = {};
      parseAttr(el.getAttribute("data-i18n-attr")).forEach(function (p) { saved[p[0]] = el.getAttribute(p[0]); });
      original.attr.set(el, saved);
    });
  }

  function applyLang(lang) {
    var en = lang === "en";
    original.html.forEach(function (es, el) {
      var key = el.getAttribute("data-i18n");
      el.innerHTML = en && EN[key] != null ? EN[key] : es;
    });
    original.attr.forEach(function (es, el) {
      parseAttr(el.getAttribute("data-i18n-attr")).forEach(function (p) {
        var value = en && EN[p[1]] != null ? EN[p[1]] : es[p[0]];
        if (value != null) el.setAttribute(p[0], value);
      });
    });
    root.lang = lang;
    document.querySelectorAll("[data-lang-link]").forEach(function (a) {
      a.setAttribute("aria-current", a.getAttribute("data-lang-link") === lang ? "true" : "false");
    });
    var url = new URL(window.location.href);
    if (lang === "en") url.searchParams.set("lang", "en"); else url.searchParams.delete("lang");
    window.history.replaceState(null, "", url.pathname + url.search + url.hash);
  }

  function ui(key, fallback) {
    return root.lang === "en" && EN[key] != null ? EN[key] : fallback;
  }

  /* ---------- Tema ---------- */
  var THEME_KEY = "pocketgb.theme";
  var media = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

  function currentTheme() {
    var forced = root.getAttribute("data-theme");
    if (forced) return forced;
    return media && media.matches ? "dark" : "light";
  }

  /* Las capturas claras/oscuras van en <picture>; con tema forzado se fija el media de cada source. */
  function syncPictures(theme) {
    var forced = root.getAttribute("data-theme");
    document.querySelectorAll("source[data-dark]").forEach(function (s) {
      s.media = forced ? (theme === "dark" ? "all" : "not all") : "(prefers-color-scheme: dark)";
    });
    document.querySelectorAll("meta[name='theme-color']").forEach(function (m) {
      if (!forced) return;
      m.setAttribute("content", theme === "dark" ? "#0B0D11" : "#ECEEF2");
    });
  }

  function setTheme(theme, persist) {
    root.setAttribute("data-theme", theme);
    if (persist) store.set(THEME_KEY, theme);
    syncPictures(theme);
    var btn = document.querySelector(".theme-toggle");
    if (btn) btn.setAttribute("aria-pressed", theme === "dark" ? "true" : "false");
  }

  /* ---------- Pestañas ---------- */
  function initTabs(list) {
    var tabs = Array.prototype.slice.call(list.querySelectorAll("[role='tab']"));
    function select(tab, focus) {
      tabs.forEach(function (t) {
        var on = t === tab;
        t.setAttribute("aria-selected", on ? "true" : "false");
        t.tabIndex = on ? 0 : -1;
        var panel = document.getElementById(t.getAttribute("aria-controls"));
        if (panel) panel.hidden = !on;
      });
      if (focus) tab.focus();
    }
    tabs.forEach(function (tab, i) {
      tab.addEventListener("click", function () { select(tab, false); });
      tab.addEventListener("keydown", function (e) {
        var next = null;
        if (e.key === "ArrowRight") next = tabs[(i + 1) % tabs.length];
        else if (e.key === "ArrowLeft") next = tabs[(i - 1 + tabs.length) % tabs.length];
        else if (e.key === "Home") next = tabs[0];
        else if (e.key === "End") next = tabs[tabs.length - 1];
        if (next) { e.preventDefault(); select(next, true); }
      });
    });
    var initial = tabs.filter(function (t) { return t.getAttribute("aria-selected") === "true"; })[0] || tabs[0];
    select(initial, false);
  }

  /* ---------- Copiar comandos ---------- */
  function initCopy(pre) {
    if (!navigator.clipboard) return;
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "copy";
    btn.textContent = ui("ui.copy", "Copiar");
    btn.addEventListener("click", function () {
      var text = pre.querySelector("code").textContent;
      navigator.clipboard.writeText(text).then(function () {
        btn.textContent = ui("ui.copied", "Copiado");
        window.setTimeout(function () { btn.textContent = ui("ui.copy", "Copiar"); }, 1600);
      }, function () { /* sin permiso: el texto sigue seleccionable */ });
    });
    pre.appendChild(btn);
  }

  /* ---------- Aparición al entrar en pantalla ---------- */
  function initReveal() {
    var items = document.querySelectorAll("[data-reveal]");
    if (!("IntersectionObserver" in window)) {
      items.forEach(function (el) { el.classList.add("is-in"); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) { entry.target.classList.add("is-in"); io.unobserve(entry.target); }
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: .12 });
    items.forEach(function (el) { io.observe(el); });
  }

  /* ---------- Arranque ---------- */
  remember();
  var lang = pickLang();
  applyLang(lang);

  var savedTheme = store.get(THEME_KEY);
  if (savedTheme === "light" || savedTheme === "dark") setTheme(savedTheme, false);
  else {
    var btn0 = document.querySelector(".theme-toggle");
    if (btn0) btn0.setAttribute("aria-pressed", currentTheme() === "dark" ? "true" : "false");
  }

  var toggle = document.querySelector(".theme-toggle");
  if (toggle) {
    toggle.addEventListener("click", function () {
      setTheme(currentTheme() === "dark" ? "light" : "dark", true);
    });
  }

  document.querySelectorAll("[data-lang-link]").forEach(function (a) {
    a.addEventListener("click", function (e) {
      e.preventDefault();
      var next = a.getAttribute("data-lang-link");
      store.set(LANG_KEY, next);
      applyLang(next);
      document.querySelectorAll(".copy").forEach(function (b) { b.textContent = ui("ui.copy", "Copiar"); });
    });
  });

  document.querySelectorAll("[role='tablist']").forEach(initTabs);
  document.querySelectorAll("pre.code:not([data-nocopy])").forEach(initCopy);
  initReveal();
})();
