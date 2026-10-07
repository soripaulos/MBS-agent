package me.rerere.rikkahub.browser

import android.webkit.WebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Compact, text-only view of the current page — the browser-use approach. Instead of a
 * screenshot (slow to capture, expensive for a vision model) or raw DOM (thousands of tokens of
 * markup), the model gets the readable text plus a numbered list of interactive elements:
 *
 *   [0] input(search) "Search products"
 *   [1] button "Sign in"
 *   [2] a "Pricing" → /pricing
 *
 * Each listed element is tagged in the DOM with `data-jev-id=<n>`, so `browser_act(index=n)`
 * (or the CSS selector `[data-jev-id="n"]`) targets it directly. Numbers are re-assigned on
 * every snapshot; always use the latest one.
 */
internal fun snapshotJs(maxElements: Int, maxTextChars: Int) = """(function(){
  try {
    document.querySelectorAll('[data-jev-id]').forEach(function(n){ n.removeAttribute('data-jev-id'); });
    var sel = 'a[href],button,input,select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=switch],[role=option],[role=combobox],[onclick],[contenteditable=true]';
    var nodes = document.querySelectorAll(sel);
    var lines = [];
    var total = 0;
    var vh = window.innerHeight;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.disabled || el.type === 'hidden') continue;
      var r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) continue;
      var st = window.getComputedStyle(el);
      if (st.visibility === 'hidden' || st.display === 'none' || st.opacity === '0') continue;
      total++;
      if (lines.length >= $maxElements) continue;
      var tag = el.tagName.toLowerCase();
      var kind = tag;
      if (tag === 'input') kind = 'input(' + (el.type || 'text') + ')';
      else if (el.getAttribute('role')) kind = tag + '[' + el.getAttribute('role') + ']';
      var label = (el.getAttribute('aria-label') || el.innerText || el.placeholder || el.title || el.alt || el.name || '').replace(/\s+/g,' ').trim();
      if (!label && el.labels && el.labels.length) label = (el.labels[0].innerText || '').replace(/\s+/g,' ').trim();
      var id = lines.length;
      el.setAttribute('data-jev-id', String(id));
      var line = '[' + id + '] ' + kind;
      if (label) line += ' "' + label.substring(0, 80) + '"';
      if ((tag === 'input' || tag === 'textarea') && el.value && el.type !== 'password') line += ' = "' + String(el.value).substring(0, 40) + '"';
      if (tag === 'select' && el.selectedOptions && el.selectedOptions.length) line += ' = "' + el.selectedOptions[0].text.substring(0, 40) + '"';
      if (el.type === 'checkbox' || el.type === 'radio') line += el.checked ? ' [x]' : ' [ ]';
      if (tag === 'a' && el.href) {
        var h = String(el.href);
        try { var u = new URL(h); h = (u.host === location.host ? '' : u.host) + u.pathname + (u.search ? u.search.substring(0, 30) : ''); } catch(e) {}
        line += ' → ' + h.substring(0, 60);
      }
      if (r.bottom < 0 || r.top > vh) line += ' (off-screen)';
      lines.push(line);
    }
    var root = document.querySelector('main, article, [role=main]') || document.body;
    var text = ((root && root.innerText) || '').replace(/[ \t]+/g,' ').replace(/\n\s*\n+/g,'\n').trim();
    var truncated = text.length > $maxTextChars;
    if (truncated) text = text.substring(0, $maxTextChars);
    return JSON.stringify({url: location.href, title: document.title, text: text, text_truncated: truncated,
      elements: lines, elements_total: total, scroll: Math.round(window.scrollY) + '/' + Math.max(0, document.documentElement.scrollHeight - vh)});
  } catch(e) { return JSON.stringify({error: 'snapshot_failed', detail: String(e)}); }
})()"""

internal fun parseJsJson(raw: String?): JsonElement? = runCatching {
    val outer = Json.parseToJsonElement(raw ?: return null)
    val inner = if (outer is JsonPrimitive && outer.isString) outer.content else outer.toString()
    Json.parseToJsonElement(inner)
}.getOrNull()

/** Snapshot of the page in this WebView, or an `{error}` object. Must run on the main thread context used by withController. */
suspend fun WebView.pageSnapshot(maxElements: Int = 80, maxTextChars: Int = 1_500): JsonObject =
    parseJsJson(evaluateJavascriptAsync(snapshotJs(maxElements.coerceIn(10, 250), maxTextChars.coerceIn(200, 20_000))))
        as? JsonObject
        ?: JsonObject(mapOf("error" to JsonPrimitive("snapshot_failed")))

/** JS acting on the element tagged `data-jev-id=[index]` by the latest snapshot. */
internal fun actJs(index: Int, action: String, text: String?): String {
    val sel = JsonPrimitive("[data-jev-id=\"$index\"]").toString()
    val txt = JsonPrimitive(text.orEmpty()).toString()
    return """(function(){
  try {
    var el = document.querySelector($sel);
    if (!el) return JSON.stringify({error:'element_gone', hint:'The page changed; take a new snapshot (browser_snapshot) and use the new numbers.'});
    el.scrollIntoView({block:'center', inline:'center'});
    var a = ${JsonPrimitive(action)};
    if (a === 'click') { el.click(); }
    else if (a === 'focus') { el.focus(); }
    else if (a === 'select') {
      if (el.tagName.toLowerCase() !== 'select') return JSON.stringify({error:'not_a_select'});
      var want = $txt.toLowerCase(); var hit = -1;
      for (var i = 0; i < el.options.length; i++) {
        var o = el.options[i];
        if (o.text.toLowerCase() === want || o.value.toLowerCase() === want) { hit = i; break; }
        if (hit < 0 && o.text.toLowerCase().indexOf(want) >= 0) hit = i;
      }
      if (hit < 0) return JSON.stringify({error:'option_not_found'});
      el.selectedIndex = hit;
      el.dispatchEvent(new Event('input', {bubbles:true}));
      el.dispatchEvent(new Event('change', {bubbles:true}));
    }
    else if (a === 'submit') {
      var form = el.form || el.closest('form');
      if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); }
      else {
        el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
        el.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
      }
    }
    else return JSON.stringify({error:'unknown_action'});
    return JSON.stringify({done:true});
  } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
})()"""
}
