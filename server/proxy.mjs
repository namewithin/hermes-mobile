// Hermes Mobile edge proxy.
// Phone -> (Tailscale / adb reverse) -> this proxy -> the Hermes Desktop app's OWN backend
// (so phone and Mac share live turns and never resume the same session twice).
// Falls back to the standalone `hermes serve` on 127.0.0.1:9119 when Desktop isn't running.
// The phone authenticates with the fixed mobile token; the proxy swaps in the upstream token.
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'

import path from 'node:path'

const STATE_DIR = process.env.HERMES_MOBILE_HOME || path.join(os.homedir(), '.hermes-mobile')
const PORT = Number(process.env.HERMES_MOBILE_PORT || 9120)
const FALLBACK_PORT = Number(process.env.HERMES_MOBILE_SERVE_PORT || 9119)
const MOBILE_TOKEN = fs.readFileSync(path.join(STATE_DIR, 'token'), 'utf8').trim()
const FALLBACK = { port: FALLBACK_PORT, token: MOBILE_TOKEN, kind: 'standalone' }
const HOME = process.env.HERMES_HOME || path.join(os.homedir(), '.hermes')

let upstream = FALLBACK
let lastKey = ''

function discoverDesktop() {
  try {
    const pids = execFileSync('pgrep', ['-f', 'serve --host 127.0.0.1 --port 0']).toString().trim().split('\n').filter(Boolean)
    for (const pid of pids) {
      const env = execFileSync('ps', ['eww', '-o', 'command=', '-p', pid]).toString()
      if (!/(^|\s)HERMES_DESKTOP=1(\s|$)/.test(env)) continue
      const home = (env.match(/(?:^|\s)HERMES_HOME=(\S+)/) || [])[1]
      if (home && home !== HOME) continue // primary profile only
      const token = (env.match(/(?:^|\s)HERMES_DASHBOARD_SESSION_TOKEN=(\S+)/) || [])[1]
      if (!token) continue
      const ls = execFileSync('lsof', ['-nP', '-a', '-p', pid, '-iTCP', '-sTCP:LISTEN']).toString()
      const port = Number((ls.match(/127\.0\.0\.1:(\d+) \(LISTEN\)/) || [])[1])
      if (port) return { port, token, kind: `desktop pid ${pid}` }
    }
  } catch {}
  return null
}

function refreshUpstream() {
  const next = discoverDesktop() || FALLBACK
  const key = `${next.kind}:${next.port}`
  if (key !== lastKey) { console.log(`[proxy] upstream -> ${next.kind} 127.0.0.1:${next.port}`); lastKey = key }
  upstream = next
}

// Loopback (emulator 10.0.2.2 / adb reverse) + Tailscale addresses only.
// The Tailscale interface is the one carrying an fd7a:115c:a1e0::/48 address; other VPNs may
// also use 100.64.0.0/10 addresses, so the IPv4 range alone is not enough.
// (The Tailscale CLI is unusable from launchd on macOS: it tries to start the GUI.)
function bindAddresses() {
  const out = new Set(['127.0.0.1'])
  for (const list of Object.values(os.networkInterfaces())) {
    const isTailscale = (list || []).some(a => a.family === 'IPv6' && a.address.toLowerCase().startsWith('fd7a:115c:a1e0'))
    if (!isTailscale) continue
    for (const a of list) {
      if (a.family === 'IPv4' || a.address.toLowerCase().startsWith('fd7a:115c:a1e0')) out.add(a.address)
    }
  }
  return [...out]
}

const isMobileToken = v => typeof v === 'string' && v === MOBILE_TOKEN

function authorize(req) {
  const u = new URL(req.url, 'http://x')
  const bearer = (req.headers.authorization || '').replace(/^Bearer\s+/i, '')
  const ok = isMobileToken(bearer) || isMobileToken(req.headers['x-hermes-session-token']) || isMobileToken(u.searchParams.get('token'))
  return ok || u.pathname === '/api/status'
}

function rewrite(req) {
  const up = upstream
  const host = `127.0.0.1:${up.port}`
  const h = { ...req.headers, host }
  if (h.origin) h.origin = `http://${host}`
  if (h.referer) h.referer = h.referer.replace(/^https?:\/\/[^/]+/, `http://${host}`)
  if (h.authorization) h.authorization = `Bearer ${up.token}`
  if (h['x-hermes-session-token']) h['x-hermes-session-token'] = up.token
  delete h['x-forwarded-host']
  const u = new URL(req.url, 'http://x')
  if (u.searchParams.has('token')) u.searchParams.set('token', up.token)
  return { up, headers: h, path: u.pathname + u.search }
}

const handler = (req, res) => {
  if (!authorize(req)) { res.writeHead(401, { 'content-type': 'application/json' }); return res.end('{"detail":"mobile token required"}') }
  if (req.url.startsWith('/__mobile/upstream')) { res.writeHead(200, { 'content-type': 'application/json' }); return res.end(JSON.stringify({ kind: upstream.kind, port: upstream.port })) }
  const { up, headers, path } = rewrite(req)
  const r2 = http.request({ host: '127.0.0.1', port: up.port, method: req.method, path, headers }, r => {
    res.writeHead(r.statusCode || 502, r.headers)
    r.pipe(res)
  })
  r2.on('error', e => { refreshUpstream(); if (!res.headersSent) res.writeHead(502); res.end(`upstream error: ${e.message}`) })
  req.pipe(r2)
}

const upgrade = (req, sock, head) => {
  if (!authorize(req)) { sock.end('HTTP/1.1 401 Unauthorized\r\n\r\n'); return }
  const { up, headers, path } = rewrite(req)
  const conn = net.connect(up.port, '127.0.0.1', () => {
    let raw = `${req.method} ${path} HTTP/1.1\r\n`
    for (const [k, v] of Object.entries(headers)) for (const vv of [].concat(v)) raw += `${k}: ${vv}\r\n`
    conn.write(raw + '\r\n')
    if (head?.length) conn.write(head)
    sock.pipe(conn).pipe(sock)
  })
  const kill = () => { sock.destroy(); conn.destroy() }
  conn.on('error', () => { refreshUpstream(); kill() }); sock.on('error', kill); conn.on('close', kill); sock.on('close', kill)
}

const servers = new Map()
function syncListeners() {
  const want = bindAddresses()
  for (const addr of want) {
    if (servers.has(addr)) continue
    const s = http.createServer(handler)
    s.on('upgrade', upgrade)
    s.keepAliveTimeout = 65000
    s.on('error', e => { console.error(`[proxy] ${addr}:${PORT} ${e.message}`); servers.delete(addr) })
    s.listen(PORT, addr, () => console.log(`[proxy] listening http://${addr.includes(':') ? `[${addr}]` : addr}:${PORT}`))
    servers.set(addr, s)
  }
  for (const [addr, s] of servers) if (!want.includes(addr)) { s.close(); servers.delete(addr); console.log(`[proxy] dropped ${addr}`) }
}

refreshUpstream()
syncListeners()
setInterval(() => { refreshUpstream(); syncListeners() }, 10000)
