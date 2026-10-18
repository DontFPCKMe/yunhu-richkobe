<script setup lang="ts">
import { inject, ref, nextTick, computed, onUnmounted } from 'vue'
import QuoteBar from './QuoteBar.vue'
import ImageEditor from './ImageEditor.vue'
import { h } from '../satori/element'
import Icon from '../console/components/Icon.vue'

const ctx = inject<any>('ctx')
const chat = ctx.chat
const quickreply = ctx.quickreply

const text = ref('')
const kind = ref(1)              // 1 文本 / 3 Markdown / 8 HTML
const ta = ref(null)
const imgInput = ref<HTMLInputElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const audioInput = ref<HTMLInputElement | null>(null)
const videoInput = ref<HTMLInputElement | null>(null)

/**
 * 输入区按钮来自插件注册表 ctx.chat.composerOf('composer')：
 * 「文本/Markdown/HTML」由 chat 插件贡献，「快捷回复」由 quickreply 贡献。
 * 快捷回复单独渲染成一行 chips（qr-row），与模式切换按钮视觉区分。
 */
const barItems = computed(() => (chat.composerOf?.('composer') || []).filter((it: any) => it.kind !== 'quick'))
const qrItems = computed(() => (chat.composerOf?.('composer') || []).filter((it: any) => it.kind === 'quick'))

/** QQ 式图标工具条：非模式切换的按钮渲染成图标（title 保留文字语义） */
const BAR_ICON: Record<string, string> = {
  emoji: 'smile', image: 'image', file: 'paperclip', voice: 'mic',
  audioFile: 'mic', videoFile: 'video', videoRec: 'record',
}

function onInput() {
  autoresize()
  if (!text.value.startsWith('/')) return
  const kw = text.value.slice(1)
  if (/\s/.test(kw)) return
  ctx.commands?.openWith?.(kw)
}

ctx.on('commands/pick', (t: string) => {
  text.value = t
  nextTick(() => { autoresize(); ta.value?.focus() })
})

function onBar(it: any) {
  if (it.kind === 'type') { kind.value = it.value; return }
  if (it.kind === 'image') { imgInput.value?.click(); return }
  if (it.kind === 'file') { fileInput.value?.click(); return }
  if (it.kind === 'quick') { quickreply?.fire?.(it.value); return }
  if (it.kind === 'emoji') { ctx.stickers?.open?.(); return }
  if (it.kind === 'voice') {
    if (ctx.stickers?.state?.recording) ctx.stickers.recordStop()
    else ctx.stickers?.recordStart?.()
    return
  }
  if (it.kind === 'audioFile') { audioInput.value?.click(); return }
  if (it.kind === 'videoFile') { videoInput.value?.click(); return }
  if (it.kind === 'videoRec') {
    const st = ctx.stickers?.state
    if (st?.recordingVideo) ctx.stickers.recordVideoStop()
    else ctx.stickers?.recordVideoStart?.()
    return
  }
}

const placeholder = computed(() => ({
  1: '输入消息，Enter 发送 / Shift+Enter 换行；粘贴或拖入图片/视频/文件会先放进待发送区',
  3: 'Markdown：# 标题、**粗体**、`代码`、- 列表 …',
  8: '<b>HTML</b> 消息，支持内联标签',
}[kind.value]))

function autoresize() {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  // 上限交给 CSS 的 max-height（40vh）钳制，这里不再写死像素
  el.style.height = el.scrollHeight + 'px'
}

/* ---------------- 待发送附件（暂存预览，不直接发送） ---------------- */
interface Pending {
  id: number
  file: File
  url: string
  kind: 'image' | 'video' | 'audio' | 'file'
  caption: string        // 图片独立配文（发送时随图后发一条文本）
  error: string          // 上次发送失败原因（保留在暂存区可重试）
}
const pending = ref<Pending[]>([])
let pendingSeq = 0

function classify(f: File): Pending['kind'] {
  if (f.type.startsWith('image/')) return 'image'
  if (f.type.startsWith('video/')) return 'video'
  if (f.type.startsWith('audio/')) return 'audio'
  return 'file'
}

/** 加入待发送区：图片/视频生成本地预览 URL（objectURL，移除/卸载时释放） */
function stage(f: File) {
  const k = classify(f)
  const url = (k === 'image' || k === 'video') ? URL.createObjectURL(f) : ''
  pending.value.push({ id: ++pendingSeq, file: f, url, kind: k, caption: '', error: '' })
}

function unstage(p: Pending) {
  pending.value = pending.value.filter((x) => x.id !== p.id)
  if (p.url) URL.revokeObjectURL(p.url)
}

/** 组件卸载释放全部 objectURL（之前漏了这条路径，切会话即泄漏） */
onUnmounted(() => { for (const p of pending.value) if (p.url) URL.revokeObjectURL(p.url) })

/** 选择框支持多选：全部进暂存区 */
async function onPick(e: any) {
  const fs: File[] = Array.from(e.target?.files || [])
  if (e.target) e.target.value = ''
  for (const f of fs) stage(f)
}

/** 粘贴：所有文件都进暂存区（之前只取第一个） */
async function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  let took = false
  for (const it of Array.from(items)) {
    if (it.kind !== 'file') continue
    const f = it.getAsFile()
    if (!f) continue
    took = true
    stage(f)
  }
  if (took) e.preventDefault()
}
function onDrop(e: DragEvent) {
  dragOver.value = false
  const fs = e.dataTransfer?.files
  if (!fs || !fs.length) return
  e.preventDefault()
  for (const f of Array.from(fs)) stage(f)
}

/* 拖到页面其他位置时阻止浏览器直接打开文件（window 级兜底） */
function winDragOver(e: DragEvent) { e.preventDefault() }
function winDrop(e: DragEvent) { e.preventDefault() }
window.addEventListener('dragover', winDragOver)
window.addEventListener('drop', winDrop)
onUnmounted(() => {
  window.removeEventListener('dragover', winDragOver)
  window.removeEventListener('drop', winDrop)
})

/* ---- 发送前图片编辑（裁剪/旋转/翻转） ---- */
const editing = ref<Pending | null>(null)
function openEditor(p: Pending) { if (p.kind === 'image') editing.value = p }
function onEdited(f: File) {
  const p = editing.value
  editing.value = null
  if (!p) return
  if (p.url) URL.revokeObjectURL(p.url)
  p.file = f
  p.url = URL.createObjectURL(f)
  p.error = ''
}

const uploading = ref(false)
const dragOver = ref(false)

/**
 * 发送：逐个上传待发送附件（失败保留在暂存区标红可重试），再发文本。
 * 视频/音频直接走 api.uploadFile + 元素树（不经 stickers 的吞异常路径）。
 */
async function submit() {
  if (uploading.value || chat.state.sending) return
  const v = text.value.trim()
  const todo = pending.value.filter((p) => !p.error || true)   // 含失败项一起重试
  if (!v && !todo.length) return
  uploading.value = true
  let attachFailed = 0
  try {
    for (const p of [...todo]) {
      p.error = ''
      try {
        if (p.kind === 'image') {
          const key: any = await ctx.api.uploadFile(p.file, 'image')
          await chat.sendMedia([h.image(typeof key === 'string' ? key : key.url || key.key)])
          if (p.caption.trim()) await chat.send(p.caption.trim(), 1)
        } else if (p.kind === 'video') {
          const key: any = await ctx.api.uploadFile(p.file, 'video')
          await chat.sendMedia([h.video(typeof key === 'string' ? key : key.key, 0)])
        } else if (p.kind === 'audio') {
          const key: any = await ctx.api.uploadFile(p.file, 'audio')
          await chat.sendMedia([h.audio(typeof key === 'string' ? key : key.key, 0)])
        } else {
          const key: any = await ctx.api.uploadFile(p.file, 'file')
          await chat.sendMedia([h.file(typeof key === 'string' ? key : key.url || key.key, p.file.name, p.file.size)])
        }
        unstage(p)
      } catch (err: any) {
        // 失败不丢附件：标红留在暂存区，下次点发送自动重试
        p.error = err?.message || '上传失败'
        attachFailed++
      }
    }
    if (v) {
      // chat.send 失败时自己会 toast，这里不再重复弹
      try { await chat.send(v, kind.value) } catch { /* 已提示 */ }
      text.value = ''
    }
    await nextTick()
    autoresize()
  } finally {
    uploading.value = false
    if (attachFailed) ctx.ui?.toast(`${attachFailed} 个附件发送失败，已保留在待发送区，可重试`, 'error')
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey) { e.preventDefault(); submit() }
}

/* ---- 快捷回复 chips 管理 ---- */
const addingQr = ref(false)
const qrNew = ref('')
function qrAdd() {
  const t = qrNew.value.trim()
  if (t) quickreply?.add?.(t)
  qrNew.value = ''
  addingQr.value = false
}

function fmtSize(n: number) {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}
</script>

<template>
  <div class="composer" :class="{ 'drag-over': dragOver }"
       @drop="onDrop" @dragover.prevent="dragOver = true" @dragleave="dragOver = false">
    <QuoteBar />
    <div class="bar-row">
      <template v-for="it in barItems" :key="it.id">
        <button v-if="it.kind === 'type'" class="ghost tiny"
                :class="{ on: kind === it.value }" @click="onBar(it)">{{ it.label }}</button>
        <button v-else class="ghost tiny icon-btn" :title="it.label" @click="onBar(it)">
          <Icon :name="BAR_ICON[it.kind] || 'page'" :size="16" />
        </button>
      </template>
      <input ref="imgInput" type="file" accept="image/*" multiple hidden @change="onPick" />
      <input ref="fileInput" type="file" multiple hidden @change="onPick" />
      <input ref="audioInput" type="file" accept="audio/*" multiple hidden @change="onPick" />
      <input ref="videoInput" type="file" accept="video/*" multiple hidden @change="onPick" />
      <span v-if="ctx.stickers?.state?.recording" class="rec-dot">
        录音中 {{ ctx.stickers.state.seconds }}s（再点「语音」结束并发送）
      </span>
      <span v-if="ctx.stickers?.state?.recordingVideo" class="rec-dot">
        录制中 {{ ctx.stickers.state.videoSeconds }}s（再点「录制视频」结束并发送）
      </span>
    </div>

    <div v-if="qrItems.length || addingQr" class="qr-row">
      <button v-for="it in qrItems" :key="it.id" class="qr-chip" @click="onBar(it)">
        {{ it.label }}
        <span class="x" title="移除" @click.stop="quickreply?.remove?.(it.value)">×</span>
      </button>
      <template v-if="addingQr">
        <input v-model="qrNew" class="qr-add-input" placeholder="新快捷回复" @keyup.enter="qrAdd" @keyup.esc="addingQr = false" />
        <button class="ghost tiny" @click="qrAdd">保存</button>
      </template>
      <button v-else class="qr-chip qr-add" title="添加快捷回复" @click="addingQr = true">+ 添加</button>
    </div>

    <!-- 待发送附件预览区：图片可点开发送前编辑，可写独立配文 -->
    <div v-if="pending.length" class="pending">
      <div v-for="p in pending" :key="p.id" class="pend" :class="{ wide: p.kind === 'image', err: !!p.error }">
        <img v-if="p.kind === 'image'" :src="p.url" alt="" title="点击编辑（裁剪/旋转/翻转）" @click="openEditor(p)" />
        <video v-else-if="p.kind === 'video'" :src="p.url" muted preload="metadata" controls></video>
        <div v-else class="pend-file">
          <Icon :name="p.kind === 'audio' ? 'mic' : 'paperclip'" :size="18" />
          <span class="nm">{{ p.file.name }}</span>
        </div>
        <span class="pend-meta">{{ p.kind === 'file' || p.kind === 'audio' ? fmtSize(p.file.size) : fmtSize(p.file.size) }}</span>
        <span v-if="p.error" class="pend-err" :title="p.error">发送失败 · 点发送重试</span>
        <button class="pend-x" title="移除" @click="unstage(p)">×</button>
        <input v-if="p.kind === 'image'" v-model="p.caption" class="pend-cap" placeholder="给这张图配一句话（可选）" />
      </div>
    </div>

    <div class="line">
      <textarea ref="ta" v-model="text" :placeholder="placeholder"
                @input="onInput" @keydown="onKey" @paste="onPaste"></textarea>
      <button :disabled="chat.state.sending || uploading || (!text.trim() && !pending.length)" @click="submit">
        {{ chat.state.sending || uploading ? '…' : '发送' }}
      </button>
    </div>

    <ImageEditor v-if="editing" :file="editing.file" :url="editing.url"
                 @done="onEdited" @close="editing = null" />
  </div>
</template>

<style scoped>
.bar-row {
  display: flex; flex-wrap: nowrap; align-items: center; gap: 6px;
  margin-bottom: 8px; padding: 2px 0 4px;
  overflow-x: auto; overflow-y: hidden; max-width: 100%;
  scrollbar-width: none; -webkit-overflow-scrolling: touch;
}
.bar-row::-webkit-scrollbar { display: none; }
.bar-row > * { flex: 0 0 auto; }
.tiny { padding: 3px 10px; font-size: 12px; border-radius: 7px; }
.bar-row .on { background: var(--acc); color: #fff; border-color: transparent; }
.line { display: flex; gap: 9px; align-items: flex-end; }
.rec-dot { font-size: 12px; color: var(--err); flex: 0 0 auto; }

/* 待发送附件预览 */
.pending { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.pend {
  position: relative; width: 84px; border-radius: 10px; overflow: hidden;
  border: 1px solid var(--line); background: var(--card2);
}
.pend.wide { width: 132px; }
.pend.err { border-color: var(--err); }
.pend img, .pend video { width: 100%; height: 84px; object-fit: cover; display: block; cursor: pointer; }
.pend-file {
  width: 100%; height: 84px; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 6px; color: var(--fg2); padding: 6px;
}
.pend-file .nm {
  font-size: 10.5px; max-width: 100%; overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap;
}
.pend-meta {
  position: absolute; left: 0; right: 0; bottom: 0; font-size: 10px; color: #fff;
  background: rgba(0, 0, 0, .45); text-align: center;
}
.pend-cap {
  width: 100%; border: none; border-top: 1px solid var(--line); background: var(--card);
  color: var(--fg); font-size: 11px; padding: 4px 6px; outline: none;
}
.pend-err {
  position: absolute; left: 0; right: 0; top: 0; font-size: 10px; color: #fff;
  background: rgba(200, 40, 40, .82); text-align: center; padding: 2px 0;
}
.pend-x {
  position: absolute; top: 3px; right: 3px; width: 18px; height: 18px; padding: 0;
  border-radius: 50%; background: rgba(0, 0, 0, .55); color: #fff; font-size: 13px; line-height: 1;
  display: grid; place-items: center;
}
.pend-x:hover { background: var(--err); }
</style>
