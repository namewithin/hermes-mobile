// Hermes Mobile shim: implements window.hermesDesktop (the Electron preload
// bridge) on top of a remote `hermes serve` reached over HTTP(S)/WS.
// Page is served at <baseUrl>/__hermes_mobile__/... so REST + WS are same-origin.
(function () {
  if (window.__hermesMobileShim) return
  window.__hermesMobileShim = true

  const N = window.HermesNative || null

  // A full-screen WebView is "focused" whenever it is visible. Android WebView often reports
  // hasFocus()=false until the first tap, which stalls desktop polls gated on window focus
  // (status/readiness stay "checking").
  try {
    Document.prototype.hasFocus = function () { return document.visibilityState === 'visible' }
  } catch (e) {}
  const readCfg = () => {
    try {
      if (N && N.getConfig) return JSON.parse(N.getConfig() || '{}')
    } catch (e) {}
    return window.__HERMES_MOBILE_CFG || {}
  }
  const cfg = readCfg()
  const baseUrl = (cfg.baseUrl || location.origin).replace(/\/+$/, '')
  const token = cfg.token || ''
  const wsBase = baseUrl.replace(/^http/, 'ws')
  const log = (...a) => {
    try { console.log('[hermes-mobile]', ...a) } catch (e) {}
  }

  const listeners = {}
  const on = name => cb => {
    ;(listeners[name] ||= new Set()).add(cb)
    return () => listeners[name].delete(cb)
  }
  const emit = (name, payload) => (listeners[name] || []).forEach(cb => { try { cb(payload) } catch (e) { log(e) } })
  window.__hermesMobileEmit = emit

  const windowState = () => ({
    isFullscreen: false,
    isMaximized: true,
    isMinimized: false,
    isVisible: document.visibilityState !== 'hidden',
    nativeOverlayWidth: 0,
    windowButtonPosition: null,
    customWindowControls: false
  })

  const connection = profile => ({
    ...windowState(),
    baseUrl,
    token,
    wsUrl: `${wsBase}/api/ws?token=${encodeURIComponent(token)}`,
    mode: 'remote',
    authMode: 'token',
    remoteKind: 'url',
    remoteHost: new URL(baseUrl).host,
    source: 'settings',
    logs: [],
    ...(profile ? { profile } : {})
  })

  function withProfile(path, profile) {
    if (!profile || path.includes('profile=')) return path
    return path + (path.includes('?') ? '&' : '?') + 'profile=' + encodeURIComponent(profile)
  }

  async function api(request) {
    const { path, method = 'GET', body, upload, timeoutMs, profile } = request || {}
    const headers = { Authorization: `Bearer ${token}`, 'X-Hermes-Session-Token': token }
    let payload
    if (upload) {
      const fd = new FormData()
      fd.append('file', new Blob([upload.bytes], { type: upload.contentType || 'application/octet-stream' }), upload.filename)
      payload = fd
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
      payload = JSON.stringify(body)
    }
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), timeoutMs || 60000)
    try {
      const res = await fetch(baseUrl + withProfile(path, profile), { method, headers, body: payload, signal: ctl.signal })
      const text = await res.text()
      if (!res.ok) throw new Error(`${res.status}: ${text}`)
      if (!text) return null
      try { return JSON.parse(text) } catch (e) { return text }
    } finally {
      clearTimeout(timer)
    }
  }

  // ---- files: picked File objects are addressed by synthetic paths ----
  const files = new Map()
  let fileSeq = 0
  const registerFile = file => {
    const p = `/mobile-upload/${++fileSeq}/${file.name || 'file'}`
    files.set(p, file)
    return p
  }
  const readAsDataUrl = file =>
    new Promise((resolve, reject) => {
      const r = new FileReader()
      r.onload = () => resolve(r.result)
      r.onerror = () => reject(r.error)
      r.readAsDataURL(file)
    })
  const readFileDataUrl = async p => {
    const f = files.get(p)
    return f ? readAsDataUrl(f) : { ok: false, error: 'not_found', message: 'File is not available on this device', path: p }
  }
  const pickFiles = (accept, multiple = true) =>
    new Promise(resolve => {
      const input = document.createElement('input')
      input.type = 'file'
      input.multiple = multiple
      if (accept) input.accept = accept
      input.style.display = 'none'
      input.onchange = () => {
        resolve(Array.from(input.files || []).map(registerFile))
        input.remove()
      }
      input.oncancel = () => { resolve([]); input.remove() }
      document.body.appendChild(input)
      input.click()
    })

  const download = (url, filename) => {
    if (N && N.download) return N.download(url, filename || '', token)
    const a = document.createElement('a')
    a.href = url
    a.download = filename || ''
    document.body.appendChild(a)
    a.click()
    a.remove()
  }

  const openExternal = url => {
    if (N && N.openExternal) N.openExternal(String(url))
    else window.open(String(url), '_blank')
    return Promise.resolve(true)
  }

  const prefs = {
    get: (k, d) => { try { const v = localStorage.getItem('hm.' + k); return v == null ? d : JSON.parse(v) } catch (e) { return d } },
    set: (k, v) => localStorage.setItem('hm.' + k, JSON.stringify(v))
  }

  const host = () => new URL(baseUrl).host
  const connectionConfig = () => ({
    envOverride: true, mode: 'remote', profile: null, remoteAuthMode: 'token', remoteOauthConnected: false,
    remoteTokenPreview: token ? token.slice(0, 4) + '…' : null, remoteTokenSet: !!token, secureTokenStorage: true,
    remoteUrl: baseUrl
  })
  const registry = () => ({
    version: 2, primary: 'mobile', launchMode: 'primary', lastUsed: 'mobile', secureTokenStorage: true,
    connections: [{ id: 'mobile', kind: 'remote', label: host(), url: baseUrl, authMode: 'token', tokenSet: !!token, tokenPreview: null }]
  })

  const explicit = {
    platform: 'android',
    isMobile: true,
    glassSupported: false,
    translucencySupported: false,
    localModelsEnabled: false,
    guestOnboardingEnabled: false,
    localSkin: null,
    getConnection: async profile => connection(profile),
    getConnectionFor: async payload => ({ ...connection(payload && payload.profile), connectionId: 'mobile', registryScoped: true }),
    getEmbedHostOrigin: async () => null,
    getProfileRoutes: async profiles => (profiles || []).map(p => ({ connectionId: 'mobile', mode: 'remote', primary: true, profile: p, targetProfile: p })),
    revalidateConnection: async () => ({ ok: true, rebuilt: false }),
    touchBackend: async () => ({ ok: true }),
    getGatewayWsUrl: async () => ({ ok: true, wsUrl: connection().wsUrl }),
    getGatewayWsUrlFor: async () => ({ ok: true, wsUrl: connection().wsUrl }),
    getAgentRoster: async () => ({ agents: [], sources: [{ connectionId: 'mobile', label: host(), kind: 'remote', reachable: true }] }),
    getPoolLimits: async () => ({ maxBackends: 0, idleMs: 0 }),
    setPoolLimits: async () => ({ maxBackends: 0, idleMs: 0 }),
    api,
    notify: async opts => {
      if (N && N.notify) N.notify(JSON.stringify(opts || {}))
      return true
    },
    getConnectionConfig: async () => connectionConfig(),
    saveConnectionConfig: async () => connectionConfig(),
    applyConnectionConfig: async () => connectionConfig(),
    testConnectionConfig: async () => ({ ok: true }),
    probeConnectionConfig: async () => ({ ok: true, authMode: 'token' }),
    connections: {
      list: async () => registry(),
      save: async () => ({ ok: true, registry: registry() }),
      remove: async () => ({ ok: false, registry: registry() }),
      setPrimary: async () => ({ ok: true, registry: registry() }),
      setLaunchMode: async () => ({ ok: true, registry: registry() }),
      setLastUsed: async () => ({ ok: true, registry: registry() }),
      test: async () => ({ ok: true }),
      onChanged: on('connections:changed')
    },
    getSecretStorageEncryption: async () => ({ on: true }),
    setSecretStorageEncryption: async () => ({ on: true }),
    profile: {
      getDefault: async () => prefs.get('defaultRoute', null),
      setDefault: async r => (prefs.set('defaultRoute', r), r),
      onDefaultChanged: on('profile:default'),
      get: async () => ({ profile: prefs.get('profile', null) }),
      remember: async p => (prefs.set('profile', p), { profile: p }),
      set: async p => (prefs.set('profile', p), { profile: p })
    },
    getPathForFile: file => (file ? registerFile(file) : ''),
    readFileDataUrl,
    readFileDataUrlForAttach: readFileDataUrl,
    readFileText: async p => {
      const f = files.get(p)
      if (!f) return { ok: false, error: 'not_found', message: 'File is not available on this device', path: p }
      return { path: p, text: await f.text(), byteSize: f.size, mimeType: f.type }
    },
    dataUrlReadMax: { get: async () => ({ defaultMaxMb: 64, maxBytes: 64 << 20, maxMb: 64 }), set: async () => ({ defaultMaxMb: 64, maxBytes: 64 << 20, maxMb: 64 }) },
    savePastedText: async () => '',
    saveClipboardImage: async () => '',
    sanitizeWorkspaceCwd: async cwd => ({ cwd: cwd || '', sanitized: false }),
    readDir: async path => ({ ok: false, error: 'unsupported', message: 'Local filesystem is not available on mobile', path, entries: [] }),
    gitRoot: async () => null,
    selectPaths: async options => {
      const exts = (options && options.filters || []).flatMap(f => f.extensions || []).filter(e => e !== '*')
      return pickFiles(exts.length ? exts.map(e => '.' + e).join(',') : '', !(options && options.multiple === false))
    },
    selectSavePath: async () => null,
    writeClipboard: async text => {
      if (N && N.writeClipboard) N.writeClipboard(String(text))
      else await navigator.clipboard.writeText(String(text))
      return true
    },
    readClipboard: async () => (N && N.readClipboard ? N.readClipboard() : navigator.clipboard.readText()),
    saveGatewayFile: async (a, b) => {
      const p = typeof a === 'object' && a ? a.path || a.url : a
      const name = (typeof a === 'object' && a && a.filename) || b || String(p).split('/').pop()
      const url = /^https?:/.test(p) ? p : `${baseUrl}/api/files/download?path=${encodeURIComponent(p)}`
      download(url, name)
      return true
    },
    saveImageFromUrl: async url => { download(url, ''); return true },
    saveImageBuffer: async () => false,
    openExternal,
    openPreviewInBrowser: openExternal,
    fetchLinkTitle: async () => '',
    resolveFavicon: async () => '',
    getVersion: async () => ({ appVersion: (N && N.appVersion ? N.appVersion() : '0.1.0'), electronVersion: '', nodeVersion: '', platform: 'android', hermesRoot: '', source: 'build' }),
    getRemoteDisplayReason: async () => null,
    getSyncStatus: async () => null,
    getBootstrapState: async () => ({ active: false, manifest: null, stages: {}, error: null, log: [], startedAt: null, completedAt: Date.now(), setupChoice: null, unsupportedPlatform: null, bundled: false }),
    probeLocalBackend: async () => ({ bootstrapNeeded: false }),
    getBootProgress: async () => ({ error: null, fakeMode: false, message: '', phase: 'ready', progress: 100, running: false, timestamp: Date.now() }),
    getOnBattery: async () => false,
    getRecentLogs: async () => ({ path: '', lines: [] }),
    revealLogs: async () => ({ ok: false, path: '' }),
    reportRendererError: () => {},
    logLine: () => {},
    zoom: { get: async () => ({ level: 0, percent: 100 }), factor: 1, setPercent: async () => ({ level: 0, percent: 100 }), onChanged: on('zoom') },
    minimizeToTray: { get: async () => ({ enabled: false, available: false }), set: async () => ({ enabled: false, available: false }), onChanged: on('mtt') },
    settings: { getDefaultProjectDir: async () => ({ defaultLabel: '', dir: null, resolvedCwd: '' }), setDefaultProjectDir: async () => ({ dir: null }), pickDefaultProjectDir: async () => ({ canceled: true, dir: null }) },
    findInPage: async () => ({ count: 0 }),
    windowControls: { custom: false, minimize() {}, toggleMaximize() {}, close() {} },
    requestMicrophoneAccess: async () => true,
    claimAmbientCue: async () => true,
    claimStartupLatency: async () => null,
    signalDeepLinkReady: () => {},
    relaunchApp: async () => location.reload()
  }

  // Generic stub for everything else: callable, chainable, `on*` returns unsubscribe.
  const stub = name =>
    new Proxy(function () {}, {
      get(_t, prop) {
        if (prop === 'then') return undefined
        if (prop === Symbol.toPrimitive) return () => ''
        if (typeof prop !== 'string') return undefined
        return stub(prop)
      },
      apply() {
        if (/^on[A-Z]/.test(name)) return () => {}
        if (/^(set|push|send|log|begin|end|move|report)/.test(name)) return undefined
        return Promise.resolve(null)
      }
    })

  const wrap = (obj, path) =>
    new Proxy(obj, {
      get(t, prop) {
        if (prop in t) {
          const v = t[prop]
          return v && typeof v === 'object' && !Array.isArray(v) ? wrap(v, path + '.' + String(prop)) : v
        }
        if (typeof prop !== 'string' || prop === 'then') return undefined
        if (/^on[A-Z]/.test(prop)) return on(prop)
        return stub(prop)
      }
    })

  // ---- narrow-screen sidebar ergonomics ----
  const visible = el => !!el && el.offsetParent !== null
  const narrowOverlay = () => [...document.querySelectorAll('[data-narrow-overlay]')].find(visible)
  const sidebarToggle = side =>
    [...document.querySelectorAll('button')].find(b =>
      (side === 'right' ? /^(Show|Hide) right sidebar/ : /^(Show|Hide) sidebar/).test(b.getAttribute('aria-label') || ''))
  const closeNarrow = ov => {
    if (!ov) return false
    const right = ov.getAttribute('data-narrow-overlay') !== 'sessions'
    const btn = sidebarToggle(right ? 'right' : 'left')
    if (btn) { btn.click(); return true }
    return false
  }
  // Tap outside an open overlay sidebar closes it; picking a session closes it too.
  document.addEventListener('click', ev => {
    const ov = narrowOverlay()
    if (!ov) return
    const t = ev.target
    if (!(t instanceof Element)) return
    if (ov.contains(t)) {
      if (t.closest('[data-slot=row-button]') && ov.getAttribute('data-narrow-overlay') === 'sessions') setTimeout(() => closeNarrow(narrowOverlay()), 120)
      return
    }
    if (t.closest('[role=dialog],[role=menu],[data-radix-popper-content-wrapper]')) return
    if (sidebarToggle('left') && sidebarToggle('left').contains(t)) return
    if (sidebarToggle('right') && sidebarToggle('right').contains(t)) return
    ev.preventDefault(); ev.stopPropagation()
    closeNarrow(ov)
  }, true)

  // Touch tap fallback: some desktop triggers (Radix menus behind tooltips) never get a
  // synthesized click from a touch tap. If a clean tap produced no click, replay it.
  let tap = null
  let clicked = false
  document.addEventListener('pointerdown', e => {
    if (e.pointerType !== 'touch' || !e.isPrimary) return
    tap = { x: e.clientX, y: e.clientY, t: Date.now(), target: e.target }
  }, true)
  document.addEventListener('click', () => { clicked = true }, true)
  document.addEventListener('pointerup', e => {
    if (e.pointerType !== 'touch' || !tap) return
    const t = tap
    tap = null
    if (Math.hypot(e.clientX - t.x, e.clientY - t.y) > 12 || Date.now() - t.t > 700) return
    clicked = false
    setTimeout(() => {
      if (clicked || !(t.target instanceof Element) || !t.target.isConnected) return
      const el = t.target.closest('button,[role=button],[role=menuitem],[role=tab],[role=option],a,[data-slot$=trigger],[aria-haspopup]')
      if (!el || el.disabled) return
      if (el.getAttribute('aria-haspopup') === 'menu' || el.getAttribute('aria-haspopup') === 'true') {
        const o = { bubbles: true, cancelable: true, button: 0, buttons: 1, pointerType: 'mouse', pointerId: 1, isPrimary: true, clientX: e.clientX, clientY: e.clientY }
        el.dispatchEvent(new PointerEvent('pointerdown', o))
        el.dispatchEvent(new PointerEvent('pointerup', { ...o, buttons: 0 }))
      } else {
        el.click()
      }
    }, 90)
  }, true)

  // Android back button: close the top-most overlay (dialog, menu, sidebar).
  window.__hermesMobileBack = () => {
    const popups = [...document.querySelectorAll('[role=dialog],[role=menu],[role=listbox],[data-radix-popper-content-wrapper]')].filter(visible)
    if (popups.length) {
      const ev = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }
      ;(document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', ev))
      return true
    }
    if (closeNarrow(narrowOverlay())) return true
    if (document.activeElement && /^(INPUT|TEXTAREA)$|true/.test(document.activeElement.tagName + document.activeElement.contentEditable)) {
      document.activeElement.blur()
      return true
    }
    return false
  }

  window.hermesDesktop = wrap(explicit, 'hermesDesktop')
  document.documentElement.classList.add('hermes-mobile')
  log('shim ready', baseUrl)
})()
