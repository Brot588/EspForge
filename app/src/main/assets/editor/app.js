/* CodePad 编辑器前端：Monaco + 与 Kotlin 的桥 */
(function () {
  'use strict';

  var editor = null;
  var suppress = false;
  var changeTimer = null;
  var currentPath = '';
  var currentAbs = '';
  var lspSeq = 1;
  var lspWait = {};
  var bpMap = {};
  var bpCollection = null;
  var dbgCollection = null;

  function lspCall(method, params, timeoutMs, lang) {
    return new Promise(function (resolve) {
      var id = lspSeq++;
      var done = false;
      lspWait[id] = function (v) {
        if (!done) {
          done = true;
          resolve(v);
        }
      };
      try {
        window.Android.lspRequest(JSON.stringify({ id: id, method: method, params: params, lang: lang || "" }));
      } catch (e) {
        resolve(null);
        return;
      }
      setTimeout(function () {
        if (!done) {
          done = true;
          delete lspWait[id];
          resolve(null);
        }
      }, timeoutMs || 4000);
    });
  }

  function lspKind(k) {
    var M = monaco.languages.CompletionItemKind;
    switch (k) {
      case 2: case 3: return M.Function;
      case 5: return M.Field;
      case 6: return M.Variable;
      case 7: return M.Class;
      case 8: return M.Interface;
      case 9: return M.Property;
      case 13: return M.Enum;
      case 14: return M.Keyword;
      case 21: return M.Constant;
      case 22: return M.Struct;
      case 25: return M.TypeParameter;
      default: return M.Text;
    }
  }

  function fileUri(path) {
    try {
      return encodeURI('file://' + path);
    } catch (e) {
      return 'file://' + path;
    }
  }

  function lspPos(position) {
    return { line: position.lineNumber - 1, character: position.column - 1 };
  }

  function registerLsp(editor, monaco) {
    if (!currentAbs) {
    }
    ['c', 'cpp', 'rust'].forEach(function (lang) {
      monaco.languages.registerCompletionItemProvider(lang, {
        triggerCharacters: ['.', '>', ':', '<', '"', '/'],
        provideCompletionItems: function (model, position) {
          if (!currentAbs) return { suggestions: [] };
          var params = {
            textDocument: { uri: fileUri(currentAbs) },
            position: lspPos(position)
          };
          return lspCall('textDocument/completion', params, lang === 'rust' ? 20000 : 4000, lang).then(function (res) {
            var items = (res && (res.items || res)) || [];
            var suggestions = items.map(function (it) {
              var doc = it.documentation;
              if (doc && typeof doc === 'object') doc = doc.value;
              return {
                label: it.label,
                kind: lspKind(it.kind),
                detail: it.detail || '',
                documentation: doc || '',
                insertText: it.insertText || it.label,
                sortText: it.sortText || it.label
              };
            });
            return { suggestions: suggestions };
          });
        }
      });

      monaco.languages.registerHoverProvider(lang, {
        provideHover: function (model, position) {
          if (!currentAbs) return null;
          var params = {
            textDocument: { uri: fileUri(currentAbs) },
            position: lspPos(position)
          };
          return lspCall('textDocument/hover', params, 4000, lang).then(function (res) {
            if (!res || !res.contents) return null;
            var c = res.contents;
            var value = typeof c === 'string' ? c : (c.value || '');
            if (!value) return null;
            return { contents: [{ value: '```c\n' + value + '\n```' }] };
          });
        }
      });

      monaco.languages.registerDefinitionProvider(lang, {
        provideDefinition: function (model, position) {
          if (!currentAbs) return null;
          var params = {
            textDocument: { uri: fileUri(currentAbs) },
            position: lspPos(position)
          };
          return lspCall('textDocument/definition', params, 4000, lang).then(function (res) {
            var loc = Array.isArray(res) ? res[0] : res;
            if (!loc) return null;
            var uri = loc.uri || loc.targetUri;
            var range = loc.range || loc.targetRange;
            if (!range) return null;
            if (uri && uri.indexOf('file://') === 0 && decodeURIComponent(uri) !== encodeURI('file://' + currentAbs)) {
              return null;
            }
            return {
              uri: model.uri,
              range: {
                startLineNumber: range.start.line + 1,
                startColumn: range.start.character + 1,
                endLineNumber: range.end.line + 1,
                endColumn: range.end.character + 1
              }
            };
          });
        }
      });
    });
  }
  function renderBreakpoints() {
    if (!bpCollection) return;
    var lines = bpMap[currentPath] || [];
    bpCollection.set(lines.map(function (ln) {
      return {
        range: new monaco.Range(ln, 1, ln, 1),
        options: { glyphMarginClassName: 'bp-glyph' }
      };
    }));
  }

  function toggleBreakpoint(line) {
    var arr = bpMap[currentPath] || [];
    var i = arr.indexOf(line);
    if (i >= 0) arr.splice(i, 1); else arr.push(line);
    bpMap[currentPath] = arr;
    renderBreakpoints();
  }

  self.MonacoEnvironment = {
    getWorkerUrl: function () {
      return '/assets/editor/vs/base/worker/workerMain.js';
    }
  };

  function callAndroid(method, arg) {
    try {
      if (!window.Android || typeof window.Android[method] !== 'function') return;
      if (typeof arg === 'undefined') window.Android[method](); else window.Android[method](arg);
    } catch (e) {
      // ignore
    }
  }

  function log(msg) {
    callAndroid('log', String(msg));
  }

  window.onerror = function (m, src, line, col, err) {
    log('JS错误: ' + m + ' @' + src + ':' + line + ':' + col);
    return false;
  };

  if (window.require && require.onError) {
    var prev = require.onError;
    require.onError = function (e) {
      log('AMD错误: ' + (e && e.message ? e.message : String(e)));
      if (prev) prev(e);
    };
  }

  function notifyChange() {
    if (suppress || !editor) return;
    if (changeTimer) clearTimeout(changeTimer);
    changeTimer = setTimeout(function () {
      callAndroid('change', editor.getValue());
    }, 250);
  }

  function defineTheme(monaco) {
    monaco.editor.defineTheme('codepad-dark', {
      base: 'vs-dark',
      inherit: true,
      rules: [
        { token: 'comment', foreground: '5C6773', fontStyle: 'italic' },
        { token: 'keyword', foreground: 'C792EA' },
        { token: 'string', foreground: 'A5E075' },
        { token: 'number', foreground: 'F78C6C' },
        { token: 'type', foreground: '4EA1FF' },
        { token: 'function', foreground: '82AAFF' },
        { token: 'variable', foreground: 'D7DCE5' }
      ],
      colors: {
        'editor.background': '#12141A',
        'editor.foreground': '#D7DCE5',
        'editorLineNumber.foreground': '#3E4552',
        'editorLineNumber.activeForeground': '#8B95A5',
        'editor.selectionBackground': '#254A73',
        'editor.lineHighlightBackground': '#181C24',
        'editorCursor.foreground': '#4EA1FF',
        'editorIndentGuide.background1': '#1E2330',
        'editorWidget.background': '#171A21',
        'editorSuggestWidget.background': '#171A21',
        'editorSuggestWidget.selectedBackground': '#22304A',
        'editorGutter.background': '#12141A',
        'scrollbarSlider.background': '#2A3140AA',
        'scrollbarSlider.hoverBackground': '#3A4356AA'
      }
    });
  }

  // 行号槽的点击在触屏上很不可靠，这里盖一层透明条，直接按坐标换算行号
  function installBreakpointStrip() {
    var strip = document.createElement('div');
    strip.id = 'bp-strip';
    document.body.appendChild(strip);

    function syncWidth() {
      try {
        var layout = editor.getLayoutInfo();
        strip.style.width = Math.max(0, layout.contentLeft) + 'px';
      } catch (e) {
      }
    }
    syncWidth();
    editor.onDidLayoutChange(syncWidth);

    function handleTap(clientX, clientY) {
      try {
        var model = editor.getModel();
        if (!model) return;
        var padding = 10;
        try {
          padding = editor.getOption(monaco.editor.EditorOption.padding).top || 0;
        } catch (e) {
        }
        var lineHeight = 20;
        try {
          lineHeight = editor.getOption(monaco.editor.EditorOption.lineHeight) || 20;
        } catch (e) {
        }
        var line = Math.floor((clientY + editor.getScrollTop() - padding) / lineHeight) + 1;
        line = Math.max(1, Math.min(model.getLineCount(), line));
        toggleBreakpoint(line);
      } catch (e) {
      }
    }

    strip.addEventListener('mousedown', function (e) {
      e.preventDefault();
      e.stopPropagation();
      handleTap(e.clientX, e.clientY);
    });
    strip.addEventListener('touchstart', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var touch = e.changedTouches && e.changedTouches[0];
      if (touch) handleTap(touch.clientX, touch.clientY);
    }, { passive: false });
    strip.addEventListener('touchmove', function (e) {
      e.preventDefault();
    }, { passive: false });
  }

  require.config({ paths: { vs: 'vs' } });

  require(['vs/editor/editor.main'], function () {
    var monaco = self.monaco;
    defineTheme(monaco);

    editor = monaco.editor.create(document.getElementById('editor'), {
      value: '',
      language: 'plaintext',
      theme: 'codepad-dark',
      automaticLayout: true,
      fontFamily: 'JetBrains Mono, Menlo, Consolas, monospace',
      fontSize: 13,
      lineHeight: 20,
      minimap: { enabled: false },
      glyphMargin: true,
      scrollBeyondLastLine: false,
      renderLineHighlight: 'line',
      smoothScrolling: true,
      cursorBlinking: 'smooth',
      padding: { top: 10, bottom: 10 },
      tabSize: 4,
      wordWrap: 'off',
      lineNumbersMinChars: 3,
      folding: true,
      contextmenu: true,
      quickSuggestions: { other: true, comments: false, strings: false },
      suggestOnTriggerCharacters: true,
      scrollbar: { verticalScrollbarSize: 8, horizontalScrollbarSize: 8 },
      overviewRulerLanes: 0
    });

    editor.onDidChangeModelContent(notifyChange);

  document.getElementById('pv-reload').addEventListener('click', refreshPreview);
  document.getElementById('pv-close').addEventListener('click', function () { CP.hidePreview(); });

    bpCollection = editor.createDecorationsCollection([]);
    dbgCollection = editor.createDecorationsCollection([]);

    editor.onMouseDown(function (e) {
      var t = e.target.type;
      // 行号槽和断点槽都算「点行号」：设/取消断点，并且不要聚焦到文本、不要弹键盘
      if (t === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN ||
          t === monaco.editor.MouseTargetType.GUTTER_LINE_NUMBERS) {
        if (e.event) e.event.preventDefault();
        toggleBreakpoint(e.target.position.lineNumber);
        return;
      }
      // 只有点在正文上才主动叫输入法
      if (t === monaco.editor.MouseTargetType.CONTENT_TEXT ||
          t === monaco.editor.MouseTargetType.CONTENT_EMPTY) {
        callAndroid('requestIme');
      }
    });

    installBreakpointStrip();
    registerLsp(editor, monaco);

    // Ctrl/Cmd + S
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, function () {
      callAndroid('save', editor.getValue());
    });
    // Ctrl/Cmd + Enter -> run
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, function () {
      callAndroid('run', editor.getValue());
    });

    callAndroid('ready');
  });

  var previewUrl = null;
  var previewTimer = null;

  function refreshPreview() {
    if (!previewUrl) return;
    var f = document.getElementById('preview-frame');
    f.src = previewUrl + (previewUrl.indexOf('?') >= 0 ? '&' : '?') + 't=' + Date.now();
  }

  window.CP = {
    load: function (payload) {
      if (!editor || !payload) return;
      var lang = payload.language || 'plaintext';
      currentPath = payload.path || '';
      currentAbs = payload.absPath || '';
      if (!monaco.languages.getLanguages().some(function (l) { return l.id === lang; })) {
        lang = 'plaintext';
      }
      var model = editor.getModel();
      suppress = true;
      if (model) {
        monaco.editor.setModelLanguage(model, lang);
        editor.setValue(payload.content || '');
      } else {
        editor.setValue(payload.content || '');
      }
      suppress = false;
      editor.setScrollTop(0);
      renderBreakpoints();
      editor.focus();
    },

    clear: function () {
      if (!editor) return;
      suppress = true;
      editor.setValue('');
      suppress = false;
    },

    requestSave: function () {
      if (editor) callAndroid('save', editor.getValue());
    },

    requestRun: function () {
      if (editor) callAndroid('run', editor.getValue());
    },

    focus: function () {
      if (editor) editor.focus();
    },

    toggleBreakpointAtCursor: function () {
      var pos = editor && editor.getPosition();
      if (pos) toggleBreakpoint(pos.lineNumber);
    },

    getCursorLine: function () {
      var pos = editor && editor.getPosition();
      return pos ? pos.lineNumber : 0;
    },

    setPosition: function (line, col) {
      editor.setPosition({ lineNumber: line, column: col });
      editor.focus();
    },

    suggest: function () {
      editor.trigger('cp-test', 'editor.action.triggerSuggest', {});
    },

    insertText: function (text) {
      editor.trigger('cp-test', 'type', { text: text });
    },

    lspRaw: function (method, params, lang) {
      lspCall(method, params, 30000, lang).then(function (v) {
        window.__lspResult = JSON.stringify(v);
      });
      return 'ok';
    },

    absPath: function () {
      return currentAbs;
    },

    lspDone: function (id, json) {
      var f = lspWait[id];
      if (!f) return;
      delete lspWait[id];
      var v = null;
      try {
        v = JSON.parse(json);
      } catch (e) {
        v = null;
      }
      f(v);
    },

    getBreakpoints: function () {
      return JSON.stringify(bpMap[currentPath] || []);
    },

    toggleBreakpoint: function (line) {
      toggleBreakpoint(line);
    },

    setDebugLine: function (line) {
      if (!dbgCollection) return;
      dbgCollection.set(line ? [{
        range: new monaco.Range(line, 1, line, 1),
        options: { isWholeLine: true, className: 'debug-line' }
      }] : []);
    },

    clearDebugLine: function () {
      if (dbgCollection) dbgCollection.set([]);
    },

    setPreview: function (url, name) {
      previewUrl = url;
      document.getElementById('preview-name').textContent = name || '预览';
      document.getElementById('preview').classList.remove('hidden');
      if (window.Android && Android.setPreviewVisible) Android.setPreviewVisible(true);
      refreshPreview();
      editor.layout();
    },

    hidePreview: function () {
      previewUrl = null;
      document.getElementById('preview').classList.add('hidden');
      document.getElementById('preview-frame').src = 'about:blank';
      if (window.Android && Android.setPreviewVisible) Android.setPreviewVisible(false);
      editor.layout();
    },

    togglePreview: function () {
      if (previewUrl) { CP.hidePreview(); }
      else if (window.Android && Android.webPreviewUrl) { CP.setPreview(Android.webPreviewUrl(), 'index.html'); }
    },

    reloadPreview: function () { refreshPreview(); },

    gotoDefinition: function () {
      var pos = editor.getPosition();
      if (!pos || !currentAbs) { return; }
      var params = {
        textDocument: { uri: fileUri(currentAbs) },
        position: { line: pos.lineNumber - 1, character: pos.column - 1 }
      };
      var lang = CP.languageId ? CP.languageId() : 'c';
      lspCall('textDocument/definition', params, 6000, lang).then(function (res) {
        var loc = Array.isArray(res) ? res[0] : res;
        if (!loc) {
          if (window.Android && Android.status) Android.status('没找到定义（文件可能不在编译数据库里）');
          return;
        }
        var uri = loc.uri || loc.targetUri || '';
        var range = loc.range || loc.targetRange;
        if (!range || !uri) { return; }
        var path = decodeURIComponent(uri.replace('file://', ''));
        var line = range.start.line + 1;
        var col = range.start.character + 1;
        if (path === currentAbs) {
          CP.gotoLine(line, col);
        } else if (window.Android && Android.openPathAt) {
          Android.openPathAt(path, line, col);
        }
      });
    },

    gotoLine: function (line, col) {
      var m = editor.getModel();
      if (!m) return;
      var ln = Math.max(1, Math.min(line, m.getLineCount()));
      editor.setPosition({ lineNumber: ln, column: Math.max(1, col || 1) });
      editor.revealLineInCenter(ln);
      editor.focus();
    },

    applySettings: function (s) {
      try {
        var o = {};
        if (s && s.fontSize) o.fontSize = s.fontSize;
        if (s && s.tabSize) o.tabSize = s.tabSize;
        if (s && typeof s.wordWrap === 'boolean') o.wordWrap = s.wordWrap ? 'on' : 'off';
        editor.updateOptions(o);
        editor.layout();
      } catch (e) {}
    },

    setReadOnly: function (v) {
      if (editor) editor.updateOptions({ readOnly: !!v });
    }
  };
})();
