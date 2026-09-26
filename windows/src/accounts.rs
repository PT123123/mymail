//! 账户列表持久化:%LOCALAPPDATA%\w-mail\accounts.json

use crate::model::{app_data_dir, AccountConfig, Res};
use std::path::PathBuf;

fn file_path() -> PathBuf {
    app_data_dir().join("accounts.json")
}

pub fn load() -> Vec<AccountConfig> {
    let _ = std::fs::create_dir_all(app_data_dir());
    let path = file_path();
    if !path.exists() {
        return Vec::new();
    }
    match std::fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
    {
        Some(v) => v,
        None => {
            // 配置损坏:备份后从头开始
            let _ = std::fs::rename(&path, path.with_extension("json.bad"));
            Vec::new()
        }
    }
}

pub fn save(accounts: &[AccountConfig]) -> Res<()> {
    let _ = std::fs::create_dir_all(app_data_dir());
    let json = serde_json::to_string_pretty(accounts)?;
    std::fs::write(file_path(), json)?;
    Ok(())
}
