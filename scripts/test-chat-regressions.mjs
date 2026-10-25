/** Offline regressions against the real plugin and compiled Composer setup. No account/API access. */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile, mkdtemp, rm } from 'node:fs/promises'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, reactive, nextTick } from 'vue'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const temp = await mkdtemp(join(root, 'node_modules', '.chat-regression-'))
const bundle = join(temp, 'subject.mjs')
await build({
  stdin: { contents: `export { chatPlugin } from './src/plugins/chat.ts';
    export { uiPlugin } from './src/plugins/ui.ts';
    export { themePlugin, PRESETS } from './src/plugins/theme.ts';
    export { themePalette, contrast } from './src/core/theme-colors.ts';
    export { messagePreview, messageQuote } from './src/core/message-preview.ts';
    export { linkify } from './src/core/render.ts';
    export { fixResourceUrls } from './src/core/res.ts';
    export { openExternalUrl } from './src/core/links.ts';
    export { default as Composer } from './src/components/Composer.vue';`, resolveDir: root },
  outfile: bundle, bundle: true, platform: 'node', format: 'esm', packages: 'external',
  plugins: [{ name: 'vue-setup', setup(b) {
    b.onLoad({ filter: /\.vue$/ }, async ({ path }) => {
      if (!path.endsWith('/Composer.vue')) return { contents: 'export default {}', loader: 'js' }
      const { descriptor } = parse(await readFile(path, 'utf8'), { filename: path })
      return { contents: compileScript(descriptor, { id: 'regression' }).content,
        loader: 'ts', resolveDir: dirname(path) }
    })
  } }],
})
const { chatPlugin, uiPlugin, themePlugin, PRESETS, themePalette, contrast, messagePreview, messageQuote, linkify, fixResourceUrls, openExternalUrl, Composer } = await import(pathToFileURL(bundle))

class MemoryStorage {
  data = new Map()
  getItem(key) { return this.data.get(key) ?? null }
  setItem(key, val) { this.data.set(key, String(val)) }
  removeItem(key) { this.data.delete(key) }
}
globalThis.localStorage = new MemoryStorage()
globalThis.window = Object.assign(new EventTarget(), { innerHeight: 800 })
globalThis.document = Object.assign(new EventTarget(), { hidden: false, visibilityState: 'visible', documentElement: { dataset: {} } })
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const message = (id, ts = 1) => ({ id, ts, senderId: 'other', sender: 'Other', type: 1, content: { text: id } })
const channel = (id, type = 2) => ({ id, type, name: id })
const turn = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve() }

function harness(overrides = {}) {
  globalThis.localStorage = new MemoryStorage()
  const listeners = new Map(), cleanups = [], emitted = []
  const api = {
    messages: async () => [], messagesBefore: async () => [], messagesAround: async () => [],
    conversations: async () => [], searchChat: async () => [], dismiss: async () => {},
    send: async () => 'sent', ...overrides,
  }
  const ctx = {
    api, auth: { state: reactive({ status: 'guest', user: { id: 'me', name: 'Me' } }) },
    storage: { load: async () => ({}), save: async () => {} },
    console: { state: { currentPage: '/' }, goto(path) { this.state.currentPage = path } },
    logger: { error() {}, info() {} },
    on(name, fn) {
      if (!listeners.has(name)) listeners.set(name, new Set())
      listeners.get(name).add(fn)
      return () => listeners.get(name).delete(fn)
    },
    emit(name, ...args) {
      emitted.push([name, ...args])
      for (const fn of [...(listeners.get(name) || [])]) fn(...args)
    },
    set(key, value) { ctx[key] = value },
    effect(fn) { const cleanup = fn(); if (cleanup) cleanups.push(cleanup); return cleanup },
    inject() {},
  }
  uiPlugin.apply(ctx)
  chatPlugin.apply(ctx)
  return { ctx, chat: ctx.chat, api, emitted, listeners, dispose() {
    for (const fn of cleanups.reverse()) fn()
    listeners.clear()
  } }
}
// A Vue renderer mounts/unmounts actual SFC setup hooks, without a browser or external DOM library.
const renderer = createRenderer({
  createElement: (tag) => ({ tag }), createText: (text) => ({ text }), createComment: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
})
function mountComposer(ctx) {
  const app = renderer.createApp({ ...Composer, render: () => null })
  app.provide('ctx', ctx)
  app.mount({})
  return { setup: app._instance.setupState, unmount: () => app.unmount() }
}
const key = (changes = {}) => ({ key: 'Enter', isComposing: false, keyCode: 13,
  shiftKey: false, ctrlKey: false, metaKey: false, preventDefault() {}, ...changes })

try {
  await test('slow old conversation cannot overwrite a newly opened conversation', async () => {
    const slow = deferred()
    const h = harness({ messages: async (id) => id === 'a' ? slow.promise : [message('b-message')] })
    try {
      const first = h.chat.open(channel('a'))
      await h.chat.open(channel('b'))
      slow.resolve([message('a-message')]); await first
      assert.deepEqual(h.chat.state.messages.map((m) => m.id), ['b-message'])
      assert.equal(h.chat.state.loadingMsgs, false)
    } finally { h.dispose() }
  })
  await test('late history/search replies cannot contaminate a new conversation', async () => {
    const history = deferred(), search = deferred()
    const h = harness({ messages: async (id) => [message(id)], messagesBefore: () => history.promise, searchChat: () => search.promise })
    try {
      await h.chat.open(channel('a'))
      const older = h.chat.loadMore(), results = h.chat.search('old')
      await h.chat.open(channel('b'))
      history.resolve([message('old-a')]); search.resolve([message('search-a')])
      await Promise.all([older, results])
      assert.deepEqual(h.chat.state.messages.map((m) => m.id), ['b'])
      assert.deepEqual(h.chat.state.searchResults, [])
      assert.equal(h.chat.state.searching, false)
    } finally { h.dispose() }
  })
  await test('only the latest search result wins and clearing invalidates an in-flight search', async () => {
    const a = deferred(), b = deferred()
    const h = harness({ searchChat: ({ word }) => word === 'a' ? a.promise : b.promise })
    try {
      await h.chat.open(channel('room'))
      const first = h.chat.search('a'), second = h.chat.search('b')
      b.resolve([message('b')]); await second; a.resolve([message('a')]); await first
      assert.equal(h.chat.state.searchResults[0].id, 'b')
      const late = deferred(); h.api.searchChat = () => late.promise
      const pending = h.chat.search('late'); await h.chat.search('')
      late.resolve([message('late')]); await pending
      assert.deepEqual(h.chat.state.searchResults, [])
    } finally { h.dispose() }
  })
  await test('polling is deduplicated, preserves pending messages and protects the history window', async () => {
    const slow = deferred(); let calls = 0
    const h = harness({ messages: () => { calls++; return slow.promise } })
    try {
      h.chat.state.current = channel('a')
      h.chat.state.messages = Array.from({ length: 210 }, (_, i) => message(String(i), i))
      h.chat.state.messages.push({ ...message('pending', 300), pending: true })
      h.chat.setAtBottom(false)
      const poll = h.chat.loadMessages(true); await h.chat.loadMessages(true)
      assert.equal(calls, 1)
      slow.resolve([message('new', 400)]); await poll
      assert.equal(h.chat.state.messages[0].id, '0')
      assert.ok(h.chat.state.messages.some((m) => m.id === 'pending'))
      h.chat.state.jumpedAway = true; await h.chat.loadMessages(true)
      assert.equal(calls, 1)
    } finally { h.dispose() }
  })
  await test('text send completing after a switch neither inserts into nor clears reply in the new conversation', async () => {
    const send = deferred(); const h = harness({ send: () => send.promise })
    try {
      await h.chat.open(channel('a'))
      const sending = h.chat.send('hello')
      await h.chat.open(channel('b')); h.chat.state.reply = { id: 'b-quote' }
      send.resolve('a-sent'); await sending
      assert.deepEqual(h.chat.state.messages, [])
      assert.equal(h.chat.state.reply.id, 'b-quote')
    } finally { h.dispose() }
  })
  await test('Enter sends, Shift+Enter inserts a newline, and IME confirmation does not send', async () => {
    const h = harness(); await h.chat.open(channel('a'))
    const sent = []; h.chat.send = async (text) => { sent.push(text) }
    const c = mountComposer(h.ctx)
    try {
      c.setup.text = '你好'; c.setup.onKey(key({ isComposing: true })); await turn()
      c.setup.onKey(key({ shiftKey: true })); await turn(); assert.equal(sent.length, 0)
      c.setup.onKey(key()); await turn(); assert.deepEqual(sent, ['你好'])
    } finally { c.unmount(); h.dispose() }
  })
  await test('reopening the same channel with refreshed metadata keeps Enter sending functional', async () => {
    const h = harness(); await h.chat.open(channel('a')); const c = mountComposer(h.ctx)
    let sent = 0; h.chat.send = async () => { sent++ }
    try {
      await h.chat.open({ ...channel('a'), name: 'refreshed' })
      c.setup.text = 'still sends'; c.setup.onKey(key()); await turn()
      assert.equal(sent, 1)
    } finally { c.unmount(); h.dispose() }
  })
  await test('failed text and edits typed during a send stay in the draft', async () => {
    const h = harness(); await h.chat.open(channel('a')); const c = mountComposer(h.ctx)
    try {
      h.chat.send = async () => { throw new Error('network') }
      c.setup.text = 'keep me'; await c.setup.submit(); assert.equal(c.setup.text, 'keep me')
      const send = deferred(); h.chat.send = () => send.promise
      const task = c.setup.submit(); c.setup.text = 'new typing'; send.resolve(); await task
      assert.equal(c.setup.text, 'new typing')
    } finally { c.unmount(); h.dispose() }
  })
  await test('drafts survive remounts, channels are isolated and event listeners are disposed', async () => {
    const h = harness(); await h.chat.open(channel('a'))
    let c = mountComposer(h.ctx)
    try {
      c.setup.text = 'draft a'; c.setup.stage(new File(['x'], 'a.txt'))
      c.unmount(); assert.equal(h.listeners.get('composer/append').size, 0)
      await h.chat.open(channel('b')); c = mountComposer(h.ctx)
      assert.equal(c.setup.text, ''); c.unmount()
      await h.chat.open(channel('a')); c = mountComposer(h.ctx)
      assert.equal(c.setup.text, 'draft a'); assert.equal(c.setup.pending.length, 1)
      h.ctx.emit('composer/append', '!'); assert.equal(c.setup.text, 'draft a!')
      assert.equal(h.chat.drafts.get(channel('a', 1)).text, '')
      h.ctx.emit('auth/logout'); assert.equal(c.setup.pending.length, 0); assert.equal(c.setup.text, '')
    } finally { c.unmount(); h.dispose() }
  })
  await test('an upload finishing after switching cannot send into the new conversation', async () => {
    const h = harness(); await h.chat.open(channel('a')); const upload = deferred(); let sent = 0
    h.api.uploadAndUrl = () => upload.promise; h.chat.sendMedia = async () => { sent++ }
    const c = mountComposer(h.ctx)
    try {
      c.setup.stage(new File(['x'], 'file.txt'))
      const task = c.setup.submit(); c.unmount(); await h.chat.open(channel('b'))
      upload.resolve({ url: 'file-key' }); await task
      assert.equal(sent, 0); assert.equal(h.chat.drafts.get(channel('a')).pending.length, 1)
    } finally { h.dispose() }
  })
  await test('a caption failure retries the caption without uploading or sending the image twice', async () => {
    const h = harness(); await h.chat.open(channel('a')); let media = 0, uploads = 0, captions = 0
    h.api.uploadAndUrl = async () => { uploads++; return { url: 'image-key' } }
    h.chat.sendMedia = async () => { media++ }
    h.chat.send = async () => { if (++captions === 1) throw new Error('network') }
    const c = mountComposer(h.ctx)
    try {
      c.setup.stage(new File(['x'], 'a.png', { type: 'image/png' })); c.setup.pending[0].caption = 'caption'
      await c.setup.submit(); assert.equal(media, 1); assert.equal(c.setup.pending.length, 1)
      await c.setup.submit(); assert.equal(media, 1); assert.equal(uploads, 1); assert.equal(c.setup.pending.length, 0)
    } finally { c.unmount(); h.dispose() }
  })
  await test('send timeout is not automatically retried as another media message', async () => {
    const h = harness(); await h.chat.open(channel('a')); let media = 0
    h.api.uploadAndUrl = async () => ({ url: 'key' })
    h.chat.sendMedia = async () => { media++; throw new Error('network timeout') }
    const c = mountComposer(h.ctx)
    try {
      c.setup.stage(new File(['x'], 'file.txt')); await c.setup.submit()
      assert.equal(media, 1); assert.equal(c.setup.pending.length, 1)
    } finally { c.unmount(); h.dispose() }
  })
  const session = (room, id, self = false) => ({ id, channel: channel(room),
    user: { id: self ? 'me' : 'other', name: 'Other' }, raw: message(id), timestamp: Date.now(), elements: [] })
  await test('message Toast skips muted/self/current conversations and deduplicates WS replays', async () => {
    const h = harness(); await h.chat.open(channel('current'))
    try {
      h.chat.state.conversations = ['normal', 'muted', 'current'].map((id) => ({ ...channel(id), unread: 0 }))
      h.chat.toggleMute('muted')
      h.ctx.emit('message-created', session('muted', 'm1'))
      h.ctx.emit('message-created', session('normal', 'mine', true))
      h.ctx.emit('message-created', session('current', 'current-msg'))
      assert.equal(h.ctx.ui.state.toasts.length, 0)
      h.ctx.emit('message-created', session('normal', 'new'))
      h.ctx.emit('message-created', session('normal', 'new'))
      assert.equal(h.ctx.ui.state.toasts.length, 1)
      assert.equal(h.chat.state.conversations.find((c) => c.id === 'normal').unread, 1)
      h.chat.toggleMute('normal'); assert.equal(h.ctx.ui.state.toasts.length, 0)
    } finally { h.dispose() }
  })
  await test('clicking a Toast navigates to the correct conversation and exact older message', async () => {
    const h = harness({ messages: async () => [message('latest')], messagesAround: async (_id, _type, mid) => [message(mid)] })
    await h.chat.open(channel('a')); h.ctx.console.state.currentPage = '/settings'
    try {
      h.chat.state.conversations = [channel('b')]
      h.ctx.emit('message-created', session('b', 'target'))
      const toast = h.ctx.ui.state.toasts[0]; assert.ok(toast.onClick)
      await h.ctx.ui.activate(toast.id)
      assert.equal(h.ctx.console.state.currentPage, '/')
      assert.equal(h.chat.state.current.id, 'b')
      assert.equal(h.chat.state.locateId, 'target')
      assert.equal(h.chat.state.messages[0].id, 'target')
      assert.equal(h.chat.state.jumpedAway, true)
    } finally { h.dispose() }
  })
  await test('late Toast location results cannot overwrite a manually selected conversation', async () => {
    const around = deferred(); const h = harness({ messagesAround: () => around.promise })
    try {
      await h.chat.open(channel('a'))
      const locating = h.chat.locate(channel('a'), 'target'); await turn()
      await h.chat.open(channel('b')); around.resolve([message('target')]); await locating
      assert.equal(h.chat.state.current.id, 'b'); assert.equal(h.chat.state.locateId, null)
    } finally { h.dispose() }
  })
  await test('Toasts are bounded, coalesce per conversation and clear on logout', async () => {
    const h = harness()
    try {
      h.ctx.ui.toast('first', 'message', 6500, { key: 'room' })
      h.ctx.ui.toast('second', 'message', 6500, { key: 'room' })
      assert.equal(h.ctx.ui.state.toasts.length, 1); assert.equal(h.ctx.ui.state.toasts[0].text, 'second')
      for (let i = 0; i < 10; i++) h.ctx.ui.toast('toast-' + i)
      assert.equal(h.ctx.ui.state.toasts.length, 5)
      h.ctx.emit('auth/logout'); assert.equal(h.ctx.ui.state.toasts.length, 0)
    } finally { h.dispose() }
  })

  await test('polling fallback notifies only new unread messages and keeps server unread counts', async () => {
    let round = 0
    const h = harness({ conversations: async () => [{ ...channel('b'), ts: ++round, unread: round }], messages: async () => [message('new-from-poll')] })
    try {
      await h.chat.loadConversations(true); await turn()
      assert.equal(h.ctx.ui.state.toasts.length, 0)
      await h.chat.loadConversations(true); await turn()
      assert.equal(h.ctx.ui.state.toasts.length, 1)
      assert.equal(h.chat.state.conversations[0].unread, 2)
      await h.ctx.ui.activate(h.ctx.ui.state.toasts[0].id)
      assert.equal(h.chat.state.current.id, 'b')
      assert.equal(h.chat.state.locateId, 'new-from-poll')
    } finally { h.dispose() }
  })
  await test('a late conversation request cannot repopulate state after logout', async () => {
    const d = deferred(); const h = harness({ conversations: () => d.promise })
    try {
      const request = h.chat.loadConversations(true)
      h.ctx.emit('auth/logout'); d.resolve([channel('stale')]); await request
      assert.deepEqual(h.chat.state.conversations, [])
    } finally { h.dispose() }
  })
  await test('media sends include the reply ID and readable quote text', async () => {
    const sent = []; const h = harness({ send: async (...args) => { sent.push(args); return 'media' } })
    try {
      await h.chat.open(channel('a'))
      h.chat.setReply({ ...message('image'), type: 2, content: { image_url: 'photo.png' } })
      await h.chat.sendMedia([{ type: 'img', attrs: { src: 'new.png' }, children: [] }])
      assert.equal(sent[0][4].quoteId, 'image')
      assert.match(sent[0][3].quote_msg_text, /\[图片\]/)
      assert.equal(h.chat.state.messages[0].quoteId, 'image')
      assert.equal(h.chat.state.reply, null)
    } finally { h.dispose() }
  })
  await test('text reply survives loss of its quoted message from the local window', async () => {
    const sent = []; const h = harness({ send: async (...args) => { sent.push(args); return 'text' } })
    try {
      await h.chat.open(channel('a'))
      h.chat.setReply(message('quoted')); h.chat.state.messages = []
      await h.chat.send('reply')
      assert.equal(sent[0][4].quoteId, 'quoted')
      assert.equal(sent[0][3].quote_msg_text, 'Other: quoted')
    } finally { h.dispose() }
  })
  await test('image/video/audio summaries show message types and image quotes have thumbnails', () => {
    assert.equal(messagePreview({ type: 2, content: { image_url: '179123-file.png' } }), '[图片]')
    assert.equal(messagePreview({ type: 10, content: { video_url: 'video.mp4' } }), '[视频]')
    assert.equal(messagePreview({ type: 11, content: { audio_url: 'audio.mp3' } }), '[语音]')
    assert.equal(messageQuote({ id: 'image', type: 2, content: { image_url: 'photo.png' } }).image, 'photo.png')
  })
  await test('multiple quotes of the same missing message share one request', async () => {
    let calls = 0; const d = deferred()
    const h = harness({ messagesAround: () => { calls++; return d.promise } })
    try {
      await h.chat.open(channel('a'))
      const a = h.chat.getQuote('image'), b = h.chat.getQuote('image')
      d.resolve([{ id: 'image', type: 2, content: { image_url: 'photo.png' } }])
      assert.equal((await a).image, 'photo.png'); assert.equal((await b).preview, '[图片]'); assert.equal(calls, 1)
    } finally { h.dispose() }
  })
  await test('a WebSocket push arriving before send acknowledgement is not inserted twice', async () => {
    const d = deferred(); const h = harness({ send: () => d.promise })
    try {
      await h.chat.open(channel('a')); const sending = h.chat.send('test')
      h.ctx.emit('message-created', session('a', 'same-id', true))
      d.resolve('same-id'); await sending
      assert.equal(h.chat.state.messages.filter((m) => m.id === 'same-id').length, 1)
    } finally { h.dispose() }
  })
  await test('active conversation still notifies when a different application page is open', async () => {
    const h = harness()
    try {
      await h.chat.open(channel('a')); h.chat.state.conversations = [channel('a')]
      h.ctx.console.state.currentPage = '/settings'
      h.ctx.emit('message-created', session('a', 'new'))
      assert.equal(h.ctx.ui.state.toasts.length, 1)
    } finally { h.dispose() }
  })
  await test('web links support www and query strings, exclude punctuation and escape HTML', () => {
    const result = linkify('看看 www.example.com； https://example.com/?a=1&b=2。<script>')
    assert.match(result, /href="https:\/\/www\.example\.com"/)
    assert.match(result, /href="https:\/\/example\.com\/\?a=1&amp;b=2"/)
    assert.ok(!result.includes('<script>'))
    assert.match(result, /&lt;script&gt;/)
  })
  await test('resource rewriting leaves relative web links and page anchors intact', () => {
    const node = (href) => ({ attrs: { href }, getAttribute(key) { return this.attrs[key] }, setAttribute(key, value) { this.attrs[key] = value } })
    const anchors = [node('#heading'), node('/help'), node('https://example.com'), node('https://chat-img.jwznb.com/a.png')]
    fixResourceUrls({ querySelectorAll(selector) { return selector === '[href]' ? anchors : [] } })
    assert.equal(anchors[0].attrs.href, '#heading'); assert.equal(anchors[1].attrs.href, '/help')
    assert.equal(anchors[2].attrs.target, '_blank')
    assert.equal(anchors[3].attrs.href, '/res/chat-img.jwznb.com/a.png')
  })
  await test('Tauri external URLs use the native command and reject unsafe protocols', async () => {
    const calls = []
    window.__TAURI__ = { core: { invoke: async (...args) => calls.push(args) } }
    try {
      await openExternalUrl('https://example.com/?a=1&b=2')
      assert.equal(calls[0][0], 'open_external_url')
      assert.equal(calls[0][1].url, 'https://example.com/?a=1&b=2')
      await assert.rejects(() => openExternalUrl('javascript:alert(1)'))
      assert.equal(calls.length, 1)
    } finally { delete window.__TAURI__ }
  })
  await test('preset palettes meet foreground, secondary text and filled-button contrast in both modes', () => {
    for (const p of PRESETS) for (const mode of ['dark', 'light']) {
      const colors = themePalette(p, mode)
      for (const bg of [colors.bg, colors.rail, colors.card, colors.card2]) {
        assert.ok(contrast(colors.fg, bg) >= 7, `${p.id}/${mode}: foreground`)
        assert.ok(contrast(colors.fg2, bg) >= 4.5, `${p.id}/${mode}: secondary`)
        assert.ok(contrast(colors.link, bg) >= 4.5, `${p.id}/${mode}: link`)
      }
      assert.ok(contrast(colors.onAccent, colors.accent) >= 4.5, `${p.id}/${mode}: button`)
    }
  })
  await test('switching light/dark mode updates preset backgrounds and survives reapplying preferences', () => {
    const originalDocument = globalThis.document
    const styles = new Map(), nodes = new Map()
    const style = { setProperty: (key, value) => styles.set(key, value), removeProperty: (key) => styles.delete(key) }
    document.documentElement.style = style
    document.getElementById = (id) => nodes.get(id)
    document.createElement = () => ({ style: {}, remove() { nodes.delete(this.id) } })
    document.head = document.body = { appendChild(node) { nodes.set(node.id, node) } }
    const h = harness()
    try {
      themePlugin.apply(h.ctx)
      h.ctx.ui.setTheme('light'); h.ctx.theme.setRadius(12)
      assert.equal(document.documentElement.dataset.theme, 'light')
      assert.equal(styles.get('--bg'), '#f5f3f9')
      assert.equal(styles.get('--acc'), '#6750a4')
      h.ctx.ui.setTheme('dark')
      assert.equal(styles.get('--bg'), '#141218')
      assert.equal(styles.get('--acc'), '#d0bcff')
    } finally { h.dispose(); globalThis.document = originalDocument }
  })
} finally { await rm(temp, { recursive: true, force: true }) }
