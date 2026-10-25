/** ui 插件：主题、Toast 提示、视图状态（主题可由配置文件的 app.theme 指定） */
import { reactive } from 'vue'
import type { Context } from '../core/context'
import { openExternalUrl } from '../core/links'

export interface ToastOptions {
  title?: string
  key?: string
  onClick?: () => void | Promise<void>
}

export const uiPlugin = {
  name: 'ui',
  provide: ['ui'],

  apply(ctx: Context, config: any = {}) {
    // 主题三态：auto（跟随系统色）/ dark / light。默认 auto；手动切换为覆盖。
    const saved = localStorage.getItem('yh.theme') || config.theme || 'auto'
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)')
    const sysDark = () => mq?.matches ?? true
    const effective = (t: string) => (t === 'auto' ? (state.systemDark ? 'dark' : 'light') : t)
    const state = reactive({ theme: saved, systemDark: sysDark(), toasts: [] as any[], panel: 'chat', confirmReq: null as any })
    let seq = 0
    const timers = new Map<number, ReturnType<typeof setTimeout>>()
    let confirmResolve: ((ok: boolean) => void) | null = null

    const ui = {
      state,
      /** 实际生效的主题（auto 解析后） */
      get effectiveTheme() { return effective(state.theme) },
      /**
       * web 确认框（替代 window.confirm）：返回 Promise<boolean>。
       * 全局单例；同一时刻只允许一个，后发的顶掉前一个（前一个按 false 结算）。
       */
      confirm(title: string, message = '', opts: { okText?: string; cancelText?: string; danger?: boolean } = {}): Promise<boolean> {
        if (confirmResolve) confirmResolve(false)
        state.confirmReq = { title, message, ...opts }
        return new Promise<boolean>((resolve) => { confirmResolve = resolve })
      },
      resolveConfirm(ok: boolean) {
        state.confirmReq = null
        const r = confirmResolve
        confirmResolve = null
        r?.(ok)
      },
      toast(text: string, type = 'info', ms = 3200, options: ToastOptions = {}) {
        // 同一会话只保留最新通知；限制堆叠，避免突发消息挤满屏幕。
        const existing = options.key && state.toasts.find((t: any) => t.key === options.key)
        const id = existing ? existing.id : ++seq
        const toast = { ...options, id, text, type }
        if (existing) Object.assign(existing, toast)
        else state.toasts.push(toast)
        clearTimeout(timers.get(id))
        timers.set(id, setTimeout(() => ui.dismiss(id), ms))
        while (state.toasts.length > 5) ui.dismiss(state.toasts[0].id)
        return id
      },
      dismiss(id: number) {
        clearTimeout(timers.get(id))
        timers.delete(id)
        const i = state.toasts.findIndex((t: any) => t.id === id)
        if (i >= 0) state.toasts.splice(i, 1)
      },
      async activate(id: number) {
        const toast = state.toasts.find((t: any) => t.id === id)
        ui.dismiss(id)
        try { await toast?.onClick?.() }
        catch (e: any) { ui.toast('打开通知失败：' + (e?.message || '未知错误'), 'error') }
      },
      setPanel(p: string) { state.panel = p },
      setTheme(t: string) {
        state.theme = t
        localStorage.setItem('yh.theme', t)
        applyTheme(effective(t))
      },
      /** 循环：dark → light → auto（跟随系统）→ dark */
      toggleTheme() {
        ui.setTheme(state.theme === 'dark' ? 'light' : state.theme === 'light' ? 'auto' : 'dark')
      },
    }

    function applyTheme(t: string) { document.documentElement.dataset.theme = t }

    ctx.effect(() => {
      applyTheme(effective(state.theme))
      // 系统色变化时，auto 模式实时跟随
      const onSys = () => { state.systemDark = sysDark(); if (state.theme === 'auto') applyTheme(effective('auto')) }
      mq?.addEventListener?.('change', onSys)
      const onExternalClick = (event: MouseEvent) => {
        if (event.defaultPrevented || event.button !== 0 || !(window as any).__TAURI__?.core?.invoke) return
        const link = (event.target as HTMLElement)?.closest?.('a[href]') as HTMLAnchorElement | null
        if (!link || link.hasAttribute('download') || !/^https?:\/\//i.test(link.href)) return
        // 同源文件下载保留浏览器默认行为。
        if (new URL(link.href).origin === location.origin) return
        event.preventDefault()
        openExternalUrl(link.href).catch((e) => ui.toast('打开链接失败：' + e.message, 'error'))
      }
      document.addEventListener('click', onExternalClick)
      return () => {
        mq?.removeEventListener?.('change', onSys)
        document.removeEventListener('click', onExternalClick)
        for (const timer of timers.values()) clearTimeout(timer)
        timers.clear()
        state.toasts = []
        ui.resolveConfirm(false)
      }
    })

    ctx.on('ui/toast', ({ text, type, ms, ...options }: any) => ui.toast(text, type, ms, options))
    ctx.on('auth/logout', () => { for (const t of [...state.toasts]) ui.dismiss(t.id) })
    ctx.set('ui', ui)
  },
}
