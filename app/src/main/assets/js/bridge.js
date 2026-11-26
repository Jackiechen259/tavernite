/*
 * Tavernite bridge — injected into the hidden SillyTavern engine WebView.
 * Transport: JSON envelopes {type, requestId?, payload} over the native
 * WebMessageListener channel "STNativeBridge".
 *
 * Design rules:
 *  - Never assume a specific ST version: feature-detect context APIs, fall
 *    back to DOM interaction.
 *  - Throttle streaming updates to ~40 ms and only emit on real changes.
 *  - rawText always comes from the chat model (editable Markdown);
 *    displayText comes from the rendered DOM (post-regex output).
 */
(function () {
  'use strict';
  if (window.__taverniteBridgeInstalled) return;
  window.__taverniteBridgeInstalled = true;

  var CHANNEL = 'STNativeBridge';
  var STREAM_THROTTLE_MS = 40;

  var ctx = null;
  var ready = false;
  var currentChatId = '';
  var generationActive = false;
  var lastStreamAt = 0;
  var lastStreamKey = '';
  var observer = null;
  var observedChatNode = null;
  var pollTimer = null;

  function log() {
    try { console.log.apply(console, ['[TaverniteBridge]'].concat(Array.prototype.slice.call(arguments))); } catch (e) {}
  }

  function post(type, payload, requestId) {
    try {
      var env = { type: type, payload: payload || {} };
      if (requestId) env.requestId = requestId;
      if (window[CHANNEL] && typeof window[CHANNEL].postMessage === 'function') {
        window[CHANNEL].postMessage(JSON.stringify(env));
      }
    } catch (e) {
      log('post failed', e);
    }
  }

  function reportError(message) {
    post('ERROR', { message: String(message) });
  }

  window.addEventListener('error', function (event) {
    if (event && event.message) reportError(event.message);
  });

  function getContext() {
    try {
      if (window.SillyTavern && typeof window.SillyTavern.getContext === 'function') {
        var c = window.SillyTavern.getContext();
        if (c && typeof c === 'object') return c;
      }
    } catch (e) {}
    return null;
  }

  function toEpochMs(value) {
    if (typeof value === 'number' && isFinite(value)) {
      return value < 100000000000 ? Math.round(value * 1000) : Math.round(value);
    }
    if (typeof value === 'string' && value) {
      var parsed = Date.parse(value);
      if (!isNaN(parsed)) return parsed;
    }
    return 0;
  }

  function roleOf(m) {
    if (m && m.is_user) return 'user';
    if (m && m.is_system) return 'system';
    return 'assistant';
  }

  function messageToDto(m, index) {
    var swipes = Array.isArray(m.swipes) ? m.swipes : null;
    var swipeId = typeof m.swipe_id === 'number' ? m.swipe_id : 0;
    var raw = typeof m.mes === 'string' ? m.mes : '';
    var id = typeof m.id === 'number' ? m.id : index;
    return {
      messageId: id,
      role: roleOf(m),
      rawText: raw,
      displayText: raw,
      swipeId: swipeId,
      swipeCount: swipes ? swipes.length : 1,
      timestamp: toEpochMs(m.send_date),
      isStreaming: false,
      characterName: m.name || null
    };
  }

  function displayedTextFor(mesId) {
    try {
      var el = document.querySelector('#chat .mes[mesid="' + mesId + '"] .mes_text');
      if (el) return el.innerText || el.textContent || '';
    } catch (e) {}
    return '';
  }

  function chatIdOf(c) {
    try {
      if (c.chatId) return String(c.chatId);
      if (c.chatMetadata && c.chatMetadata.chat_id) return String(c.chatMetadata.chat_id);
      if (c.chatMetadata && c.chatMetadata.chatId) return String(c.chatMetadata.chatId);
      if (c.characters && typeof c.this_chid === 'number' && c.characters[c.this_chid]) {
        return String(c.characters[c.this_chid].avatar || c.this_chid);
      }
    } catch (e) {}
    return currentChatId;
  }

  function characterIdOf(c) {
    try {
      if (c.characters && typeof c.this_chid === 'number' && c.characters[c.this_chid]) {
        var ch = c.characters[c.this_chid];
        return String(ch.avatar || c.this_chid);
      }
    } catch (e) {}
    return '';
  }

  function emitCharacters() {
    if (!ctx) return;
    var chars = Array.isArray(ctx.characters) ? ctx.characters : [];
    var list = [];
    for (var i = 0; i < chars.length; i++) {
      var ch = chars[i] || {};
      var data = ch.data || {};
      var id = ch.avatar ? String(ch.avatar) : String(i);
      list.push({
        id: id,
        name: ch.name || data.name || 'Unknown',
        avatarUrl: ch.avatar ? '/characters/' + encodeURIComponent(ch.avatar) : null,
        tags: Array.isArray(data.tags) ? data.tags : (Array.isArray(ch.tags) ? ch.tags : []),
        lastMessageDate: toEpochMs(ch.date_last_chat || ch.date_add),
        description: ch.description || data.description || null
      });
    }
    post('CHARACTERS_SYNC', { characters: list });
  }

  function emitChatLoaded() {
    if (!ctx) return;
    var chat = Array.isArray(ctx.chat) ? ctx.chat : [];
    var messages = chat.map(function (m, i) {
      var dto = messageToDto(m, i);
      var display = displayedTextFor(dto.messageId);
      if (display) dto.displayText = display;
      return dto;
    });
    currentChatId = chatIdOf(ctx);
    post('CHAT_LOADED', {
      chatId: currentChatId,
      characterId: characterIdOf(ctx),
      messages: messages
    });
  }

  function emitPresets() {
    if (!ctx) return;
    var presets = [];
    var active = null;
    try {
      var pm = ctx.presetManager || window.presetManager;
      if (pm) {
        if (typeof pm.getPresetList === 'function') {
          presets = (pm.getPresetList() || []).map(function (p) { return p && (p.name || p.id); }).filter(Boolean);
        }
        if (typeof pm.getSelectedPresetName === 'function') active = pm.getSelectedPresetName();
        else if (pm.selectedPresetName) active = pm.selectedPresetName;
      }
      if (!presets.length && window.oai_settings && Array.isArray(window.oai_settings.presets)) {
        presets = window.oai_settings.presets.map(function (p) { return p && p.name; }).filter(Boolean);
      }
      if (!active && typeof ctx.getPresetName === 'function') active = ctx.getPresetName();
    } catch (e) { log('presets', e); }

    var activeWorld = [];
    try {
      var meta = ctx.chat_metadata || {};
      var world = meta.world_info || meta.worldInfo;
      if (world) activeWorld = [String(world)];
    } catch (e) {}

    var personas = [];
    try {
      if (ctx.power_user && ctx.power_user.personas) {
        Object.keys(ctx.power_user.personas).forEach(function (k) {
          var p = ctx.power_user.personas[k];
          if (typeof p === 'string') personas.push(p);
          else if (p && p.name) personas.push(p.name);
        });
      }
      if (ctx.name1) personas.unshift(ctx.name1);
    } catch (e) {}

    post('PRESETS_SYNC', {
      activePreset: active,
      presets: presets,
      activeWorldInfo: activeWorld,
      personas: Array.from(new Set(personas)).slice(0, 50)
    });
  }

  function emitReady() {
    if (!ctx) return;
    ready = true;
    post('ENGINE_READY', {
      version: ctx.version || window.SILLYTAVERN_VERSION || 'unknown',
      currentUser: ctx.name1 || null
    });
    emitCharacters();
    emitChatLoaded();
    emitPresets();
  }

  function setGenerating(value, reason) {
    if (generationActive === value) return;
    generationActive = value;
    post('GENERATION_STATE', { isGenerating: value, reason: reason || null });
    if (!value) setTimeout(refreshLastMessage, 200);
  }

  function emitStreamToken() {
    if (!ctx) return;
    var chat = Array.isArray(ctx.chat) ? ctx.chat : [];
    if (!chat.length) return;
    var index = chat.length - 1;
    var m = chat[index];
    var mesId = typeof m.id === 'number' ? m.id : index;
    var display = displayedTextFor(mesId) || m.mes || '';
    var raw = m.mes || '';
    var key = mesId + '|' + raw.length + '|' + display.length;
    var now = Date.now();
    if (key === lastStreamKey) return;
    if (now - lastStreamAt < STREAM_THROTTLE_MS) return;
    lastStreamAt = now;
    lastStreamKey = key;
    post('STREAM_TOKEN', {
      messageId: mesId,
      swipeId: typeof m.swipe_id === 'number' ? m.swipe_id : 0,
      rawText: raw,
      displayText: display
    });
  }

  function refreshLastMessage() {
    if (!ctx) return;
    var chat = Array.isArray(ctx.chat) ? ctx.chat : [];
    if (!chat.length) return;
    var index = chat.length - 1;
    var m = chat[index];
    var mesId = typeof m.id === 'number' ? m.id : index;
    var dto = messageToDto(m, index);
    var display = displayedTextFor(mesId);
    if (display) dto.displayText = display;
    post('MESSAGE_UPDATED', { messageId: mesId, message: dto });
    emitChatLoaded();
  }

  function attachObserver() {
    var chatEl = document.getElementById('chat');
    if (!chatEl) return;
    if (observer && observedChatNode === chatEl) return;
    if (observer) observer.disconnect();
    observedChatNode = chatEl;
    observer = new MutationObserver(function () {
      if (generationActive) emitStreamToken();
    });
    observer.observe(chatEl, { childList: true, subtree: true, characterData: true });
  }

  function hookEvents() {
    if (!ctx) return;
    var es = ctx.eventSource;
    var et = ctx.event_types || {};
    if (!es || typeof es.on !== 'function' || es.__taverniteHooked) return;
    es.__taverniteHooked = true;
    function on(name, fn) { if (name) { try { es.on(name, fn); } catch (e) {} } }
    on(et.GENERATION_STARTED, function () { setGenerating(true, 'started'); });
    on(et.GENERATION_AFTER_COMMANDS, function () { setGenerating(true, 'after_commands'); });
    on(et.GENERATION_ENDED, function () { setGenerating(false, 'ended'); });
    on(et.GENERATION_STOPPED, function () { setGenerating(false, 'stopped'); });
    on(et.MESSAGE_SENT, function () { setTimeout(refreshLastMessage, 120); emitChatLoaded(); });
    on(et.MESSAGE_RECEIVED, function () { setTimeout(refreshLastMessage, 120); emitChatLoaded(); });
    on(et.MESSAGE_EDITED, function () { setTimeout(refreshLastMessage, 120); emitChatLoaded(); });
    on(et.MESSAGE_DELETED, function () { emitChatLoaded(); });
    on(et.MESSAGE_SWIPED, function () { setTimeout(refreshLastMessage, 120); emitChatLoaded(); });
    on(et.CHAT_CHANGED, function () { currentChatId = chatIdOf(ctx); emitChatLoaded(); emitPresets(); });
    on(et.CHARACTER_EDITED, function () { emitCharacters(); });
    on(et.CHARACTER_DELETED, function () { emitCharacters(); });
    on(et.CHARACTER_DUPLICATED, function () { emitCharacters(); });
    on(et.WORLDINFO_SETTINGS_UPDATED, function () { emitPresets(); });
    on(et.SETTINGS_UPDATED, function () { emitPresets(); });
    ['STREAM_TOKEN_RECEIVED', 'STREAM_TOKEN', 'STREAMING_TOKEN'].forEach(function (key) {
      if (et[key]) on(et[key], emitStreamToken);
    });
  }

  function detectGenerationFallback() {
    if (!ctx || ctx.eventSource) return;
    var stopBtn = document.getElementById('stop_but') || document.getElementById('mes_stop');
    if (!stopBtn) return;
    var visible = stopBtn.offsetParent !== null;
    if (visible !== generationActive) setGenerating(visible, 'dom');
  }

  function poll() {
    if (!ctx) {
      ctx = getContext();
      if (ctx) {
        attachObserver();
        hookEvents();
        emitReady();
      }
      return;
    }
    if (!ready) emitReady();
    detectGenerationFallback();
    attachObserver();
  }

  // ---------- Native commands ----------

  function mesElement(mesId) {
    return document.querySelector('#chat .mes[mesid="' + mesId + '"]');
  }

  function clickIf(el) {
    if (!el) return false;
    try { el.click(); return true; } catch (e) { return false; }
  }

  function selectCharacter(payload) {
    if (!ctx) return false;
    var id = String(payload.characterId || '');
    try {
      if (typeof ctx.selectCharacterById === 'function') { ctx.selectCharacterById(id); return true; }
      if (typeof window.selectCharacterById === 'function') { window.selectCharacterById(id); return true; }
    } catch (e) { log('selectCharacterById', e); }
    var chars = Array.isArray(ctx.characters) ? ctx.characters : [];
    var index = -1;
    for (var i = 0; i < chars.length; i++) {
      if (String((chars[i] && chars[i].avatar) || i) === id || String(i) === id) { index = i; break; }
    }
    if (index < 0) return false;
    var el = document.querySelector('#rm_print_characters_block .character_select[chid="' + index + '"]') ||
             document.querySelector('#rm_print_characters_block [data-chid="' + index + '"]');
    return clickIf(el);
  }

  function attachImages(attachments) {
    if (!Array.isArray(attachments) || !attachments.length) return;
    attachments.forEach(function (a) {
      try {
        var mime = a.mime || 'image/png';
        var binary = atob(a.base64 || '');
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        var blob = new Blob([bytes], { type: mime });
        var file = new File([blob], 'tavernite-upload.' + (mime.split('/')[1] || 'png'), { type: mime });
        var dt = new DataTransfer();
        dt.items.add(file);
        var input = document.getElementById('file_form_input') || document.querySelector('input[type="file"]');
        if (input) {
          input.files = dt.files;
          input.dispatchEvent(new Event('change', { bubbles: true }));
        }
      } catch (e) { log('attach image', e); }
    });
  }

  function sendMessage(payload) {
    if (!ctx) return false;
    var text = String(payload.text || '');
    if (!text) return false;
    attachImages(payload.attachments);
    try {
      if (typeof ctx.sendMessageAsUser === 'function') {
        ctx.sendMessageAsUser(text);
        return true;
      }
    } catch (e) { log('sendMessageAsUser', e); }
    var ta = document.getElementById('send_textarea');
    if (!ta) return false;
    ta.value = text;
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    return clickIf(document.getElementById('send_but'));
  }

  function executeSlash(payload, requestId) {
    var command = String(payload.command || '');
    if (!command) return false;
    try {
      if (typeof ctx.executeSlashCommandsWithOptions === 'function') {
        Promise.resolve(ctx.executeSlashCommandsWithOptions(command)).then(function (result) {
          post('SLASH_RESULT', { ok: true, output: result != null ? String(result) : null }, requestId);
        }).catch(function (e) {
          post('SLASH_RESULT', { ok: false, output: String(e) }, requestId);
        });
        return true;
      }
      if (window.SlashCommandParser && typeof window.SlashCommandParser.commandFromString === 'function') {
        var cmd = window.SlashCommandParser.commandFromString(command);
        if (cmd && typeof cmd.execute === 'function') {
          Promise.resolve(cmd.execute()).then(function (result) {
            post('SLASH_RESULT', { ok: true, output: result != null ? String(result) : null }, requestId);
          }).catch(function (e) {
            post('SLASH_RESULT', { ok: false, output: String(e) }, requestId);
          });
          return true;
        }
      }
    } catch (e) {
      post('SLASH_RESULT', { ok: false, output: String(e) }, requestId);
      return true;
    }
    var input = document.getElementById('send_textarea');
    if (input) {
      input.value = command;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      return clickIf(document.getElementById('send_but'));
    }
    return false;
  }

  function swipeMessage(payload) {
    var el = mesElement(payload.messageId);
    if (!el) return false;
    var dir = payload.direction === 'left' ? 'left' : 'right';
    return clickIf(el.querySelector('.swipe_' + dir)) ||
           clickIf(el.querySelector('.swipe' + dir)) ||
           clickIf(el.querySelector('[data-swipe="' + dir + '"]'));
  }

  function editMessage(payload) {
    if (!ctx) return false;
    var mesId = payload.messageId;
    var text = String(payload.newRawText || '');
    try {
      if (Array.isArray(ctx.chat) && ctx.chat[mesId]) {
        ctx.chat[mesId].mes = text;
        if (typeof ctx.saveChat === 'function') Promise.resolve(ctx.saveChat()).catch(function () {});
        var textEl = mesElement(mesId);
        if (textEl) {
          var target = textEl.querySelector('.mes_text');
          if (target) target.textContent = text;
        }
        var dto = messageToDto(ctx.chat[mesId], mesId);
        dto.displayText = text;
        post('MESSAGE_UPDATED', { messageId: mesId, message: dto });
        return true;
      }
    } catch (e) { log('editMessage', e); }
    return false;
  }

  function deleteMessage(payload) {
    if (!ctx) return false;
    var mesId = payload.messageId;
    var following = !!payload.deleteFollowing;
    try {
      if (Array.isArray(ctx.chat) && mesId >= 0 && mesId < ctx.chat.length) {
        if (typeof ctx.deleteMessage === 'function') {
          ctx.deleteMessage(mesId, following);
          setTimeout(refreshLastMessage, 150);
          return true;
        }
        ctx.chat.splice(mesId, following ? ctx.chat.length - mesId : 1);
        if (typeof ctx.saveChat === 'function') Promise.resolve(ctx.saveChat()).catch(function () {});
        setTimeout(refreshLastMessage, 150);
        return true;
      }
    } catch (e) { log('deleteMessage', e); }
    var el = mesElement(mesId);
    return clickIf(el && el.querySelector('.mes_delete'));
  }

  function stopGeneration() {
    if (!ctx) return false;
    try {
      if (typeof ctx.stopGeneration === 'function') { ctx.stopGeneration(); return true; }
      if (typeof window.stopGeneration === 'function') { window.stopGeneration(); return true; }
    } catch (e) { log('stopGeneration', e); }
    return clickIf(document.getElementById('stop_but'));
  }

  function continueGeneration() {
    try {
      if (ctx && typeof ctx.continue === 'function') { ctx.continue(); return true; }
    } catch (e) {}
    return executeSlash({ command: '/continue' }, null);
  }

  function updateAuthorNote(payload) {
    if (!ctx) return false;
    var note = String(payload.text || '');
    var depth = typeof payload.depth === 'number' ? payload.depth : 4;
    try {
      var meta = ctx.chatMetadata || ctx.chat_metadata;
      if (meta) {
        meta.note_prompt = note;
        meta.note_depth = depth;
        if (typeof ctx.saveMetadata === 'function') ctx.saveMetadata();
        return true;
      }
    } catch (e) { log('authorNote', e); }
    return executeSlash({ command: '/note ' + note }, null);
  }

  function setPreset(payload) {
    var name = String(payload.presetName || '');
    if (!name) return false;
    try {
      var pm = ctx && (ctx.presetManager || window.presetManager);
      if (pm && typeof pm.selectPreset === 'function') { pm.selectPreset(name); return true; }
    } catch (e) { log('setPreset', e); }
    return executeSlash({ command: '/preset ' + name }, null);
  }

  function toggleWorldInfo(payload) {
    if (!ctx) return false;
    var name = String(payload.name || '');
    var enabled = !!payload.enabled;
    try {
      var worlds = ctx.world_names || window.world_names;
      if (Array.isArray(worlds)) {
        for (var i = 0; i < worlds.length; i++) {
          var w = worlds[i];
          if (w && (w.name === name || w === name)) {
            if (typeof w === 'object') w.enabled = enabled;
            if (typeof ctx.saveSettings === 'function') ctx.saveSettings();
            return true;
          }
        }
      }
    } catch (e) { log('worldInfo', e); }
    return false;
  }

  var handlers = {
    SELECT_CHARACTER: selectCharacter,
    SEND_MESSAGE: sendMessage,
    STOP_GENERATION: stopGeneration,
    SWIPE_MESSAGE: swipeMessage,
    EDIT_MESSAGE: editMessage,
    DELETE_MESSAGE: deleteMessage,
    CONTINUE_GENERATION: continueGeneration,
    REGENERATE: function () { return executeSlash({ command: '/regenerate' }, null); },
    EXECUTE_SLASH_CMD: executeSlash,
    UPDATE_AUTHOR_NOTE: updateAuthorNote,
    SET_PRESET: setPreset,
    TOGGLE_WORLD_INFO: toggleWorldInfo,
    REQUEST_CHARACTERS: function () { emitCharacters(); return true; },
    REQUEST_CURRENT_CHAT: function () { emitChatLoaded(); return true; }
  };

  window.__taverniteReceive = function (json) {
    var envelope;
    try { envelope = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return; }
    if (!envelope || !envelope.type) return;
    var handler = handlers[envelope.type];
    if (!handler) { log('unknown command', envelope.type); return; }
    try {
      var ok = handler(envelope.payload || {}, envelope.requestId);
      if (envelope.type === 'EXECUTE_SLASH_CMD') return;
      post('SLASH_RESULT', { ok: !!ok, output: null }, envelope.requestId);
    } catch (e) {
      reportError('Command ' + envelope.type + ' failed: ' + e);
    }
  };

  pollTimer = setInterval(poll, 500);
  window.addEventListener('beforeunload', function () {
    if (pollTimer) clearInterval(pollTimer);
    if (observer) observer.disconnect();
  });

  log('installed');
})();
