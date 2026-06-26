//! D Company ERP — Tauri shell.
//!
//! Wraps the React frontend in a native window for macOS, Windows, and Linux.
//! The shell exposes one IPC command (`app_info`) so the UI can show the
//! current build version and platform; everything else flows over normal
//! HTTPS to the cloud backend.

use serde::Serialize;

#[derive(Serialize)]
struct AppInfo {
    name: &'static str,
    version: &'static str,
    platform: &'static str,
}

#[tauri::command]
fn app_info() -> AppInfo {
    AppInfo {
        name: "D Company ERP",
        version: env!("CARGO_PKG_VERSION"),
        platform: std::env::consts::OS,
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .invoke_handler(tauri::generate_handler![app_info])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
