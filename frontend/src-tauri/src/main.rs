// Prevent a console window from popping up alongside the app on Windows release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    d_company_erp_lib::run()
}
