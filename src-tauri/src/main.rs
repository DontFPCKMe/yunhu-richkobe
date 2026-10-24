// 云湖牢大版 Tauri 壳：
//   · 启动时拉起 sidecar（resources/node.exe + resources/server-bundle.mjs），
//     环境变量 YH_ROOT 指向资源目录（server 从那里读 dist/config/certs）
//   · 等 127.0.0.1:8902 可连接后创建主窗口加载 http://127.0.0.1:8902
//     （与 Electron 版同模型：前端零改动，全部接口走同源代理）
//   · 托盘图标：左键 show/focus 主窗口，右键弹出菜单（含中文「退出」）
//   · 主窗口销毁 / 托盘「退出」时杀掉 sidecar 子进程
//   · 加固：任何一步失败都不 panic（release 下 panic=abort 会让整个 app 崩），
//     连不上后端时在窗口里显示可读的中文错误页，而不是白屏/闪退。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::TcpStream;
use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{
    image::Image,
    menu::{Menu, MenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, Url, WebviewUrl, WebviewWindowBuilder,
};

const PORT: u16 = 8902;

/// sidecar 子进程句柄：用全局 static 持有，避开 Tauri State 在
/// on_window_event 闭包里的生命周期问题（E0597）
static SIDECAR: std::sync::OnceLock<Mutex<Option<Child>>> = std::sync::OnceLock::new();
fn sidecar_slot() -> &'static Mutex<Option<Child>> {
    SIDECAR.get_or_init(|| Mutex::new(None))
}

/// 杀掉 sidecar 子进程（take 出句柄后 kill+wait）。
/// 幂等：句柄已被 take 走则本次为空操作，供托盘「退出」与窗口 Destroyed 两处复用。
fn kill_sidecar() {
    if let Ok(mut guard) = sidecar_slot().lock() {
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

fn wait_port(port: u16, timeout: Duration) -> bool {
    let start = Instant::now();
    while start.elapsed() < timeout {
        if TcpStream::connect(("127.0.0.1", port)).is_ok() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    false
}

/// 极简 HTML 转义，防止把文件路径里的 & < > 直接塞进页面破坏结构。
fn html_escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

/// 生成一张自带样式、可读的中文错误页（含"重试"按钮，点了跳回 sidecar 地址）。
fn error_html(msg: &str) -> String {
    format!(
        r#"<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>云湖牢大版</title>
<style>
  html,body{{height:100%;margin:0}}
  body{{display:flex;align-items:center;justify-content:center;
       font-family:"Microsoft YaHei",system-ui,sans-serif;
       background:#0f1115;color:#e6e6e6}}
  .card{{max-width:520px;padding:32px 28px;text-align:center;
        background:#171a21;border:1px solid #262b36;border-radius:14px}}
  h1{{font-size:20px;margin:0 0 12px}}
  p{{font-size:14px;line-height:1.7;color:#b9c0cc;margin:0 0 20px;word-break:break-all}}
  button{{font-size:14px;padding:10px 22px;border:0;border-radius:8px;
         background:#3b82f6;color:#fff;cursor:pointer}}
  button:hover{{background:#2f6fe0}}
</style>
</head>
<body>
  <div class="card">
    <h1>无法连接到后端服务</h1>
    <p>{msg}</p>
    <button onclick="location.href='http://127.0.0.1:{port}'">重试连接</button>
  </div>
</body>
</html>"#,
        msg = html_escape(msg),
        port = PORT
    )
}

/// 在已存在的主窗口里写入错误页。窗口不存在（如已关闭）时静默跳过。
fn show_error_page(app: &AppHandle, msg: &str) {
    let Some(win) = app.get_webview_window("main") else {
        return;
    };
    let html = error_html(msg);
    // 用 serde_json 把整段 HTML 序列化成合法的 JS 字符串字面量，规避所有转义问题。
    let js = format!(
        "document.open();document.write({});document.close();",
        serde_json::to_string(&html).unwrap_or_else(|_| String::from("\"\""))
    );
    let _ = win.eval(js);
    let _ = win.set_title("云湖牢大版（后端未启动）");
}

/// 后台监控 sidecar：每 2s try_wait 一次，中途退出就提示用户。
/// 句柄被取走（窗口关闭时 Destroyed 分支 kill 掉了子进程）或锁异常时自动停止。
/// ⚠️ 借用纪律：先在锁内算出结果并结束借用，再在锁外改容器——
///    `guard.as_mut()` 的借用与 `*guard = None` 交叠会触发 E0502/E0499。
fn spawn_sidecar_monitor(app: AppHandle) {
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(2));
        let exited = match sidecar_slot().lock() {
            Ok(mut guard) => {
                let st = guard.as_mut().map(|c| c.try_wait()); // 借用在此结束
                match st {
                    Some(Ok(Some(_status))) => {
                        *guard = None; // 进程已退出，清空句柄
                        true
                    }
                    Some(Ok(None)) => false, // 仍在运行
                    Some(Err(_)) => {
                        *guard = None;
                        true
                    }
                    // 句柄已被取走（正常关闭流程或已处理过），停止监控
                    None => return,
                }
            }
            // 锁中毒：panic=abort 下几乎不会发生，保守停止
            Err(_) => return,
        };
        if exited {
            let app2 = app.clone();
            let _ = app.run_on_main_thread(move || {
                show_error_page(&app2, "后端服务已意外退出，请点击下方按钮重试，或重启应用。")
            });
            return; // 只提示一次
        }
    });
}

fn main() {
    let result = tauri::Builder::default()
        .setup(|app| {
            // 先 clone handle 再进闭包/线程，避免借用冲突
            let handle = app.handle().clone();

            // 资源目录（安装后 = 安装目录/resources）。
            // resource_dir 失败退回当前目录，当前目录也失败退回 "."，全程不 panic。
            let res: PathBuf = handle
                .path()
                .resource_dir()
                .unwrap_or_else(|_| std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")));
            let node = res.join("node.exe");
            let bundle = res.join("server-bundle.mjs");
            let resources_ok = node.exists() && bundle.exists();

            // 拉起 sidecar（端口已被占用时复用现有实例，避免双开冲突）
            if !wait_port(PORT, Duration::from_millis(300)) {
                if resources_ok {
                    match Command::new(&node)
                        .arg(&bundle)
                        .env("YH_ROOT", &res)
                        .env("PORT", PORT.to_string())
                        .spawn()
                    {
                        Ok(child) => {
                            if let Ok(mut guard) = sidecar_slot().lock() {
                                *guard = Some(child);
                            }
                            // 仅在成功拉起子进程后才启动中途退出监控
                            spawn_sidecar_monitor(handle.clone());
                        }
                        Err(e) => eprintln!("[tauri] sidecar 启动失败: {e}"),
                    }
                } else {
                    eprintln!(
                        "[tauri] 缺少运行文件：node={} bundle={}（存在={}）",
                        node.display(),
                        bundle.display(),
                        resources_ok
                    );
                }
            }

            // 等服务就绪（最多 20s）后在主线程建窗口。
            // 未就绪时加载 about:blank 再注入错误页，避免连接失败白屏。
            let win_handle = handle.clone();
            std::thread::spawn(move || {
                let ready = wait_port(PORT, Duration::from_secs(20));
                let inner = win_handle.clone();
                let _ = win_handle.run_on_main_thread(move || {
                    // 就绪→加载 sidecar；未就绪→about:blank（都是必然可解析的字面量）。
                    let target = if ready {
                        format!("http://127.0.0.1:{PORT}").parse::<Url>().ok()
                    } else {
                        "about:blank".parse::<Url>().ok()
                    };
                    let Some(target) = target else {
                        eprintln!("[tauri] 无法构造窗口 URL，跳过建窗");
                        return;
                    };

                    let title = if ready {
                        "云湖牢大版"
                    } else {
                        "云湖牢大版（服务启动中…）"
                    };
                    let builder = WebviewWindowBuilder::new(
                        &inner,
                        "main",
                        WebviewUrl::External(target),
                    )
                    .title(title)
                    .inner_size(1280.0, 820.0)
                    .min_inner_size(900.0, 600.0);

                    match builder.build() {
                        Ok(win) => {
                            let _ = win.show();
                            let _ = win.set_focus();
                            if !ready {
                                let msg = if resources_ok {
                                    format!(
                                        "后端服务未能在 20 秒内启动（端口 {PORT}）。\
                                         可能是首次启动较慢或被安全软件拦截，请点击重试。"
                                    )
                                } else {
                                    format!(
                                        "缺少运行所需文件（node.exe 或 server-bundle.mjs），\
                                         安装可能不完整。资源目录：{}。请重新安装应用。",
                                        res.display()
                                    )
                                };
                                show_error_page(&inner, &msg);
                            }
                        }
                        Err(e) => eprintln!("[tauri] 窗口创建失败: {e}"),
                    }
                });
            });

            // 托盘右键菜单：中文「退出」项（id = "quit"）
            let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&quit])?;

            // 托盘：复用 bundle 的窗口图标 + 右键菜单；无图标时用空 Image 兜底
            //（TrayIconBuilder::icon 内部 try_into().ok()，空图不会 panic）。
            let icon = handle
                .default_window_icon()
                .cloned()
                .unwrap_or_else(|| Image::new(&[], 0, 0));
            // 托盘构建失败不再中断整个 app（原来是 .build(app)? 会让 setup 返回 Err）。
            if let Err(e) = TrayIconBuilder::new()
                .icon(icon)
                .tooltip("云湖牢大版")
                .menu(&menu)
                // 关键：左键不弹菜单，保持 show/focus 主窗口的行为；右键由系统弹出上面的菜单
                .show_menu_on_left_click(false)
                // 菜单项点击：「退出」先杀 sidecar，再退出应用
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "quit" => {
                        kill_sidecar();
                        app.exit(0);
                    }
                    _ => {}
                })
                // 托盘图标事件：仅左键单击时唤出/聚焦主窗口
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(win) = app.get_webview_window("main") {
                            let _ = win.show();
                            let _ = win.set_focus();
                        }
                    }
                })
                .build(app)
            {
                eprintln!("[tauri] 托盘创建失败（不影响主程序）: {e}");
            }

            Ok(())
        })
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                // 主窗口关闭 = 清理 sidecar（kill_sidecar 幂等，托盘退出路径已 take 过则为空操作）
                kill_sidecar();
            }
        })
        .run(tauri::generate_context!());

    // 原来这里是 .expect("error while running tauri application")，
    // panic=abort 下会直接 abort。改成打印后以非零码干净退出。
    if let Err(e) = result {
        eprintln!("[tauri] 应用运行出错: {e}");
        std::process::exit(1);
    }
}
