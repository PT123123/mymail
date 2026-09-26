mod accounts;
mod db;
mod dpapi;
mod imap;
mod model;
mod smtp;
mod sync;

use db::Db;
use model::*;
use slint::{ComponentHandle, ModelRc, VecModel};
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::{Arc, Mutex};
use sync::Engine;

slint::include_modules!();

pub struct AppState {
    pub accounts: Vec<AccountConfig>,
    pub account_index: Option<usize>,
    pub engines: HashMap<String, Arc<Engine>>,
    pub folders: Vec<FolderInfo>,
    pub folder_index: Option<usize>,
    pub messages: Vec<MessageHeader>,
    pub message_index: Option<usize>,
    pub current_html_path: Option<String>,
    pub current_body_text: String,
    pub loaded_account: Option<String>,
}

#[derive(Clone)]
pub struct Ui {
    pub win: slint::Weak<MainWindow>,
    pub state: Arc<Mutex<AppState>>,
    pub db: Db,
}

impl Ui {
    pub fn post(&self, f: impl FnOnce(&MainWindow) + Send + 'static) {
        let win = self.win.clone();
        let _ = slint::invoke_from_event_loop(move || {
            if let Some(w) = win.upgrade() {
                f(&w);
            }
        });
    }

    pub fn status(&self, s: impl ToString) {
        let s = s.to_string();
        self.post(move |w| w.set_status(s.into()));
    }

    pub fn reload_folders(&self) {
        let ui = self.clone();
        self.post(move |w| {
            let items = {
                let st = ui.state.lock().unwrap();
                st.folders
                    .iter()
                    .map(|f| FolderItem {
                        display: if f.full_name == "INBOX" {
                            "收件箱".into()
                        } else {
                            f.name.clone().into()
                        },
                        unread_text: f.unread.to_string().into(),
                        has_unread: f.unread > 0,
                    })
                    .collect::<Vec<_>>()
            };
            w.set_folders(ModelRc::new(VecModel::from(items)));
        });
    }

    pub fn reload_messages(&self) {
        let ui = self.clone();
        self.post(move |w| {
            let items = {
                let st = ui.state.lock().unwrap();
                st.messages
                    .iter()
                    .map(|m| MessageItem {
                        uid: m.uid.to_string().into(),
                        subject: if m.subject.is_empty() {
                            "(无主题)".into()
                        } else {
                            m.subject.clone().into()
                        },
                        from: m.from.clone().into(),
                        date: fmt_date(m.date_unix, "%m-%d %H:%M").into(),
                        snippet: m.snippet.clone().into(),
                        seen: m.is_seen(),
                        flagged: m.is_flagged(),
                        has_attach: m.has_attach,
                    })
                    .collect::<Vec<_>>()
            };
            w.set_messages(ModelRc::new(VecModel::from(items)));
        });
    }

    /// 后台线程调用:把缓存库里的文件夹(含未读数)刷进 UI 状态
    pub fn reload_folders_from_db(&self) {
        let account_id = {
            let st = self.state.lock().unwrap();
            let Some(idx) = st.account_index else { return };
            match st.accounts.get(idx) {
                Some(a) => a.id.clone(),
                None => return,
            }
        };
        let folders = {
            let conn = self.db.lock().unwrap();
            db::list_folders(&conn, &account_id).unwrap_or_default()
        };
        {
            let mut st = self.state.lock().unwrap();
            st.folders = folders;
        }
        self.reload_folders();
    }

    /// 后台线程调用:把缓存库里 folder_id 的邮件列表载入 UI 状态。
    /// 若用户已切到别的文件夹则跳过,避免后台线程(回填/IDLE)覆盖当前视图。
    pub fn reload_messages_from_db(&self, folder_id: i64) {
        let msgs = {
            let conn = self.db.lock().unwrap();
            db::list_messages(&conn, folder_id, 500).unwrap_or_default()
        };
        let mut applied = false;
        {
            let mut st = self.state.lock().unwrap();
            let current = st.folder_index.and_then(|i| st.folders.get(i)).map(|f| f.id);
            if current == Some(folder_id) {
                // 列表被重排时(如新邮件插到最前)放弃旧选中,避免操作错邮件
                if let Some(idx) = st.message_index {
                    if st.messages.get(idx).map(|m| m.uid) != msgs.get(idx).map(|m| m.uid) {
                        st.message_index = None;
                    }
                }
                st.messages = msgs;
                applied = true;
            }
        }
        if applied {
            self.reload_messages();
        }
    }
}

pub struct Prefill {
    to: String,
    cc: String,
    subject: String,
    body: String,
    in_reply_to: Option<String>,
}

fn main() -> anyhow::Result<()> {
    let db = db::open();
    let state = Arc::new(Mutex::new(AppState {
        accounts: accounts::load(),
        account_index: None,
        engines: HashMap::new(),
        folders: Vec::new(),
        folder_index: None,
        messages: Vec::new(),
        message_index: None,
        current_html_path: None,
        current_body_text: String::new(),
        loaded_account: None,
    }));

    let main = MainWindow::new()?;
    let ui = Ui {
        win: main.as_weak(),
        state: state.clone(),
        db: db.clone(),
    };

    wire_main(&main, &ui);
    refresh_account_model(&main, &state);

    // 初始选中第一个账户(触发 changed → 加载文件夹)
    if !state.lock().unwrap().accounts.is_empty() {
        main.set_account_index(0);
    }

    main.run()?;
    Ok(())
}

fn engine_for(st: &mut AppState, account_id: &str, db: &Db) -> Option<Arc<Engine>> {
    if let Some(e) = st.engines.get(account_id) {
        return Some(e.clone());
    }
    let acc = st.accounts.iter().find(|a| a.id == account_id)?.clone();
    match Engine::new(acc, db.clone()) {
        Ok(e) => {
            let e = Arc::new(e);
            st.engines.insert(account_id.to_string(), e.clone());
            Some(e)
        }
        Err(err) => {
            eprintln!("引擎初始化失败:{err}");
            None
        }
    }
}

fn current_engine(ui: &Ui) -> Option<Arc<Engine>> {
    let mut st = ui.state.lock().unwrap();
    let idx = st.account_index?;
    let acc_id = st.accounts.get(idx)?.id.clone();
    engine_for(&mut st, &acc_id, &ui.db)
}

fn current_message(ui: &Ui) -> Option<(Arc<Engine>, MessageHeader)> {
    let mut st = ui.state.lock().unwrap();
    let idx = st.message_index?;
    let header = st.messages.get(idx)?.clone();
    let eng = engine_for(&mut st, &header.account_id, &ui.db)?;
    Some((eng, header))
}

fn refresh_account_model(w: &MainWindow, state: &Arc<Mutex<AppState>>) {
    let st = state.lock().unwrap();
    let names: Vec<String> = st
        .accounts
        .iter()
        .map(|a| {
            if a.display_name.is_empty() {
                a.email.clone()
            } else {
                a.display_name.clone()
            }
        })
        .collect();
    w.set_account_names(ModelRc::new(VecModel::from(
        names
            .into_iter()
            .map(Into::into)
            .collect::<Vec<slint::SharedString>>(),
    )));
    w.set_has_account(!st.accounts.is_empty());
}

fn fmt_date(ts: i64, fmt: &str) -> String {
    chrono::DateTime::from_timestamp(ts, 0)
        .map(|d| d.with_timezone(&chrono::Local).format(fmt).to_string())
        .unwrap_or_default()
}

fn extract_addr(display: &str) -> String {
    match (display.find('<'), display.find('>')) {
        (Some(s), Some(e)) if e > s => display[s + 1..e].trim().to_string(),
        _ => display.trim().to_string(),
    }
}

fn split_addresses(text: &str) -> Vec<String> {
    text.split([';', ',', '\n', '\r'])
        .map(|s| s.trim().to_string())
        .filter(|s| s.contains('@'))
        .collect()
}

fn wire_main(main: &MainWindow, ui: &Ui) {
    // ---------- 账户切换 ----------
    {
        let ui = ui.clone();
        main.on_account_changed(move |idx| {
            if idx < 0 {
                return;
            }
            let eng = {
                let mut st = ui.state.lock().unwrap();
                st.account_index = Some(idx as usize);
                st.folder_index = None;
                st.folders.clear();
                st.messages.clear();
                let Some(acc) = st.accounts.get(idx as usize).cloned() else {
                    return;
                };
                if st.loaded_account.as_deref() == Some(acc.id.as_str()) {
                    return;
                }
                st.loaded_account = Some(acc.id.clone());
                engine_for(&mut st, &acc.id, &ui.db)
            };
            let Some(eng) = eng else {
                ui.status("账户初始化失败");
                return;
            };
            let ui = ui.clone();
            std::thread::spawn(move || match eng.refresh_folders() {
                Ok(_) => {
                    ui.reload_folders_from_db();
                    ui.status("就绪");
                }
                Err(e) => {
                    // 离线:回退到缓存里的文件夹列表
                    if eng.list_folders().map(|f| !f.is_empty()).unwrap_or(false) {
                        ui.reload_folders_from_db();
                        ui.status(format!("离线模式:{e}"));
                    } else {
                        ui.status(format!("连接失败:{e}"));
                        ui.state.lock().unwrap().loaded_account = None;
                    }
                }
            });
        });
    }

    // ---------- 文件夹 ----------
    {
        let ui = ui.clone();
        main.on_folder_selected(move |idx| {
            let (eng, folder) = {
                let mut st = ui.state.lock().unwrap();
                st.folder_index = Some(idx as usize);
                st.message_index = None;
                let Some(folder) = st.folders.get(idx as usize).cloned() else {
                    return;
                };
                let eng = st
                    .account_index
                    .and_then(|i| st.accounts.get(i))
                    .map(|a| a.id.clone())
                    .and_then(|id| engine_for(&mut st, &id, &ui.db));
                (eng, folder)
            };
            let Some(eng) = eng else {
                ui.status("请先连接账户");
                return;
            };
            let ui = ui.clone();
            std::thread::spawn(move || eng.open_folder(folder, ui));
        });
    }

    // ---------- 读信 ----------
    {
        let ui = ui.clone();
        main.on_message_selected(move |idx| {
            let (eng, header) = {
                let mut st = ui.state.lock().unwrap();
                st.message_index = Some(idx as usize);
                let Some(header) = st.messages.get(idx as usize).cloned() else {
                    return;
                };
                let eng = engine_for(&mut st, &header.account_id, &ui.db);
                (eng, header)
            };
            let Some(eng) = eng else {
                ui.status("请先连接账户");
                return;
            };
            let ui = ui.clone();
            std::thread::spawn(move || match eng.fetch_body(&header) {
                Ok((body, html_path, new_flags)) => {
                    {
                        let mut st = ui.state.lock().unwrap();
                        st.current_body_text = body.text.clone().unwrap_or_default();
                        st.current_html_path = html_path.clone();
                        for m in st.messages.iter_mut() {
                            if m.uid == header.uid {
                                m.flags = new_flags;
                                m.has_attach = m.has_attach || !body.attachments.is_empty();
                            }
                        }
                    }
                    ui.post(move |w| {
                        w.set_message_open(true);
                        w.set_reader_subject(
                            if header.subject.is_empty() { "(无主题)".to_string() } else { header.subject.clone() }.into(),
                        );
                        w.set_reader_from(header.from.clone().into());
                        w.set_reader_date(fmt_date(header.date_unix, "%Y-%m-%d %H:%M").into());
                        w.set_reader_body(body.text.clone().unwrap_or_default().into());
                        w.set_reader_has_html(body.html.is_some());
                        w.set_reader_flagged(new_flags & FLAG_FLAGGED != 0);
                        w.set_reader_seen(new_flags & FLAG_SEEN != 0);
                        let atts: Vec<AttachmentItem> = body
                            .attachments
                            .iter()
                            .map(|a| AttachmentItem { name: a.file_name.clone().into() })
                            .collect();
                        w.set_reader_attachments(ModelRc::new(VecModel::from(atts)));
                    });
                    ui.reload_messages();
                    ui.status("已加载");
                }
                Err(e) => ui.status(format!("加载失败:{e}")),
            });
        });
    }

    // ---------- 标志操作 ----------
    {
        let ui = ui.clone();
        main.on_toggle_read_clicked(move || {
            let (eng, header) = match current_message(&ui) {
                Some(v) => v,
                None => return,
            };
            let value = !header.is_seen();
            let ui = ui.clone();
            std::thread::spawn(move || match eng.set_seen(&header, value) {
                Ok(new_flags) => {
                    update_state_flags(&ui, header.uid, new_flags);
                    ui.reload_messages();
                    ui.post(move |w| w.set_reader_seen(value));
                }
                Err(e) => ui.status(format!("标记失败:{e}")),
            });
        });
    }
    {
        let ui = ui.clone();
        main.on_toggle_flag_clicked(move || {
            let (eng, header) = match current_message(&ui) {
                Some(v) => v,
                None => return,
            };
            let ui = ui.clone();
            std::thread::spawn(move || match eng.toggle_flag(&header) {
                Ok(new_flags) => {
                    update_state_flags(&ui, header.uid, new_flags);
                    ui.reload_messages();
                    ui.post(move |w| w.set_reader_flagged(new_flags & FLAG_FLAGGED != 0));
                }
                Err(e) => ui.status(format!("标记失败:{e}")),
            });
        });
    }
    {
        let ui = ui.clone();
        main.on_delete_clicked(move || {
            let (eng, header) = match current_message(&ui) {
                Some(v) => v,
                None => return,
            };
            let confirmed = rfd::MessageDialog::new()
                .set_title("删除邮件")
                .set_description("把这封邮件移到垃圾桶?")
                .set_buttons(rfd::MessageButtons::YesNo)
                .show();
            if confirmed != rfd::MessageDialogResult::Yes {
                return;
            }
            let ui = ui.clone();
            std::thread::spawn(move || match eng.delete(&header) {
                Ok(()) => {
                    remove_from_state(&ui, header.uid);
                    ui.reload_messages();
                    ui.post(|w| {
                        w.set_message_open(false);
                        w.set_reader_body("".into());
                    });
                    ui.status("已删除");
                }
                Err(e) => ui.status(format!("删除失败:{e}")),
            });
        });
    }
    {
        let ui = ui.clone();
        main.on_move_clicked(move || {
            let (eng, header, folder) = match current_message(&ui) {
                Some((e, h)) => {
                    let folder = {
                        let st = ui.state.lock().unwrap();
                        st.folder_index.and_then(|i| st.folders.get(i).cloned())
                    };
                    match folder {
                        Some(f) => (e, h, f),
                        None => return,
                    }
                }
                None => return,
            };
            let (names, target_ids): (Vec<String>, Vec<i64>) = {
                let st = ui.state.lock().unwrap();
                st.folders
                    .iter()
                    .filter(|f| f.id != folder.id)
                    .cloned()
                    .map(|f| {
                        (
                            if f.full_name == "INBOX" { "收件箱".to_string() } else { f.name.clone() },
                            f.id,
                        )
                    })
                    .unzip()
            };
            let dialog = MoveDialog::new().unwrap();
            dialog.set_folder_names(ModelRc::new(VecModel::from(
                names.into_iter().map(Into::into).collect::<Vec<_>>(),
            )));
            {
                let ui = ui.clone();
                let header = header.clone();
                let eng = eng.clone();
                let dialog_w = dialog.as_weak();
                dialog.on_confirm(move |idx| {
                    if idx < 0 {
                        return;
                    }
                    let Some(dest) = target_ids.get(idx as usize).copied() else {
                        return;
                    };
                    if let Some(d) = dialog_w.upgrade() {
                        let _ = d.hide();
                    }
                    let eng = eng.clone();
                    let header = header.clone();
                    let ui = ui.clone();
                    std::thread::spawn(move || match eng.move_message(&header, dest) {
                        Ok(()) => {
                            remove_from_state(&ui, header.uid);
                            ui.reload_messages();
                            ui.post(|w| w.set_message_open(false));
                            ui.status("已移动");
                        }
                        Err(e) => ui.status(format!("移动失败:{e}")),
                    });
                });
            }
            {
                let dialog_w = dialog.as_weak();
                dialog.on_cancel(move || {
                    if let Some(d) = dialog_w.upgrade() {
                        let _ = d.hide();
                    }
                });
            }
            let _ = dialog.show();
        });
    }

    // ---------- 附件 / HTML ----------
    {
        let ui = ui.clone();
        main.on_open_html_clicked(move || {
            let path = ui.state.lock().unwrap().current_html_path.clone();
            if let Some(p) = path {
                if let Err(e) = open::that_detached(&p) {
                    ui.status(format!("打开失败:{e}"));
                }
            }
        });
    }
    {
        let ui = ui.clone();
        main.on_attachment_clicked(move |name| {
            let (eng, header) = match current_message(&ui) {
                Some(v) => v,
                None => return,
            };
            let Some(file) = rfd::FileDialog::new()
                .set_title("保存附件")
                .set_file_name(&name)
                .save_file()
            else {
                return;
            };
            let ui = ui.clone();
            std::thread::spawn(move || {
                match eng.save_attachment(&header, &name, std::path::Path::new(&file)) {
                    Ok(()) => {
                        ui.status(format!("附件已保存:{}", file.display()));
                        let _ = open::that_detached(&file);
                    }
                    Err(e) => ui.status(format!("附件下载失败:{e}")),
                }
            });
        });
    }

    // ---------- 写信 ----------
    {
        let ui = ui.clone();
        main.on_compose_clicked(move || open_compose(&ui, None));
    }
    {
        let ui = ui.clone();
        main.on_reply_clicked(move || open_reply(&ui, false, false));
    }
    {
        let ui = ui.clone();
        main.on_reply_all_clicked(move || open_reply(&ui, true, false));
    }
    {
        let ui = ui.clone();
        main.on_forward_clicked(move || open_reply(&ui, false, true));
    }

    // ---------- 添加账户 ----------
    {
        let ui = ui.clone();
        main.on_add_account_clicked(move || open_account_dialog(&ui));
    }
}

fn update_state_flags(ui: &Ui, uid: u32, flags: i64) {
    let mut st = ui.state.lock().unwrap();
    for m in st.messages.iter_mut() {
        if m.uid == uid {
            m.flags = flags;
        }
    }
}

fn remove_from_state(ui: &Ui, uid: u32) {
    let mut st = ui.state.lock().unwrap();
    st.messages.retain(|m| m.uid != uid);
    st.message_index = None;
}

fn open_reply(ui: &Ui, reply_all: bool, forward: bool) {
    let prefill = {
        let st = ui.state.lock().unwrap();
        let Some(idx) = st.message_index else { return };
        let Some(h) = st.messages.get(idx) else { return };
        let (subject, body, to, cc, in_reply_to) = if forward {
            (
                if h.subject.starts_with("Fw:") {
                    h.subject.clone()
                } else {
                    format!("Fw: {}", h.subject)
                },
                format!(
                    "\n\n---------- 转发的邮件 ----------\n发件人:{}\n主题:{}\n\n{}",
                    h.from,
                    h.subject,
                    st.current_body_text
                ),
                String::new(),
                String::new(),
                None,
            )
        } else {
            let subject = if h.subject.starts_with("Re:") {
                h.subject.clone()
            } else {
                format!("Re: {}", h.subject)
            };
            let to = extract_addr(&h.from);
            let cc = if reply_all {
                h.to
                    .split([';', ','])
                    .map(|s| s.trim().to_string())
                    .filter(|s| s.contains('@'))
                    .collect::<Vec<_>>()
                    .join("; ")
            } else {
                String::new()
            };
            (
                subject,
                quote_for_reply(&h.from, h.date_unix, &st.current_body_text),
                to,
                cc,
                h.message_id.clone(),
            )
        };
        Prefill {
            to,
            cc,
            subject,
            body,
            in_reply_to,
        }
    };
    open_compose(ui, Some(prefill));
}

fn open_compose(ui: &Ui, prefill: Option<Prefill>) {
    let win = ComposeWindow::new().unwrap();
    if let Some(p) = &prefill {
        win.set_to_text(p.to.clone().into());
        win.set_cc_text(p.cc.clone().into());
        win.set_subject_text(p.subject.clone().into());
        win.set_body_text(p.body.clone().into());
    }

    let att_paths: Rc<RefCell<Vec<String>>> = Rc::new(RefCell::new(Vec::new()));
    let att_model: Rc<VecModel<AttachmentItem>> = Rc::new(VecModel::from(Vec::new()));
    win.set_attachments(ModelRc::new(att_model.clone()));

    {
        let win_w = win.as_weak();
        let att_paths = att_paths.clone();
        let att_model = att_model.clone();
        win.on_add_attachment_clicked(move || {
            let Some(files) = rfd::FileDialog::new()
                .set_title("添加附件")
                .add_filter("所有文件", &["*"])
                .pick_files()
            else {
                return;
            };
            for f in files {
                let name = f
                    .file_name()
                    .map(|s| s.to_string_lossy().into_owned())
                    .unwrap_or_else(|| "attachment".into());
                att_paths.borrow_mut().push(f.to_string_lossy().into_owned());
                att_model.push(AttachmentItem { name: name.into() });
            }
            if let Some(w) = win_w.upgrade() {
                w.set_status(format!("已添加 {} 个附件", att_paths.borrow().len()).into());
            }
        });
    }

    fn build_input(win: &ComposeWindow, paths: &[String], in_reply_to: Option<String>) -> ComposeInput {
        ComposeInput {
            to: split_addresses(&win.get_to_text()),
            cc: split_addresses(&win.get_cc_text()),
            bcc: vec![],
            subject: win.get_subject_text().to_string(),
            body: win.get_body_text().to_string(),
            attachments: paths.to_vec(),
            in_reply_to,
        }
    }

    {
        let win_w = win.as_weak();
        let ui = ui.clone();
        let att_paths = att_paths.clone();
        let in_reply_to = prefill.as_ref().and_then(|p| p.in_reply_to.clone());
        win.on_send_clicked(move || {
            let Some(w) = win_w.upgrade() else { return };
            let Some(eng) = current_engine(&ui) else {
                w.set_status("请先选择账户".into());
                return;
            };
            let input = build_input(&w, &att_paths.borrow(), in_reply_to.clone());
            if input.to.is_empty() && input.cc.is_empty() {
                w.set_status("请至少填写一个收件人".into());
                return;
            }
            w.set_busy(true);
            w.set_status("发送中…".into());
            let win_w2 = win_w.clone();
            let ui = ui.clone();
            std::thread::spawn(move || match eng.send(&input) {
                Ok(()) => {
                    ui.status("已发送");
                    ui.post(move |_| {
                        if let Some(cw) = win_w2.upgrade() {
                            let _ = cw.hide();
                        }
                    });
                }
                Err(e) => {
                    let msg = format!("发送失败:{e}");
                    let _ = slint::invoke_from_event_loop(move || {
                        if let Some(cw) = win_w2.upgrade() {
                            cw.set_busy(false);
                            cw.set_status(msg.into());
                        }
                    });
                }
            });
        });
    }
    {
        let win_w = win.as_weak();
        let ui = ui.clone();
        let att_paths = att_paths.clone();
        let in_reply_to = prefill.as_ref().and_then(|p| p.in_reply_to.clone());
        win.on_save_draft_clicked(move || {
            let Some(w) = win_w.upgrade() else { return };
            let Some(eng) = current_engine(&ui) else {
                w.set_status("请先选择账户".into());
                return;
            };
            let input = build_input(&w, &att_paths.borrow(), in_reply_to.clone());
            w.set_busy(true);
            w.set_status("保存草稿…".into());
            let win_w2 = win_w.clone();
            let ui = ui.clone();
            std::thread::spawn(move || match eng.save_draft(&input) {
                Ok(()) => {
                    ui.status("草稿已保存到服务器");
                    ui.post(move |_| {
                        if let Some(cw) = win_w2.upgrade() {
                            let _ = cw.hide();
                        }
                    });
                }
                Err(e) => {
                    let msg = format!("保存草稿失败:{e}");
                    let _ = slint::invoke_from_event_loop(move || {
                        if let Some(cw) = win_w2.upgrade() {
                            cw.set_busy(false);
                            cw.set_status(msg.into());
                        }
                    });
                }
            });
        });
    }
    {
        let win_w = win.as_weak();
        win.on_cancel_clicked(move || {
            if let Some(w) = win_w.upgrade() {
                let _ = w.hide();
            }
        });
    }
    let _ = win.show();
}

fn open_account_dialog(ui: &Ui) {
    let win = AccountDialog::new().unwrap();
    {
        let win_w = win.as_weak();
        win.on_preset_selected(move |idx| {
            let Some(w) = win_w.upgrade() else { return };
            let (imap_host, imap_port, smtp_host, smtp_port) = match idx {
                1 => ("imap.qq.com", "993", "smtp.qq.com", "465"),
                2 => ("imap.163.com", "993", "smtp.163.com", "465"),
                3 => ("imap.gmail.com", "993", "smtp.gmail.com", "465"),
                4 => ("outlook.office365.com", "993", "smtp.office365.com", "587"),
                _ => ("", "993", "", "465"),
            };
            w.set_imap_host(imap_host.into());
            w.set_imap_port(imap_port.into());
            w.set_smtp_host(smtp_host.into());
            w.set_smtp_port(smtp_port.into());
        });
    }

    fn build_config(win: &AccountDialog) -> anyhow::Result<AccountConfig> {
        let email = win.get_email().trim().to_string();
        let password = win.get_password().to_string();
        let imap_host = win.get_imap_host().trim().to_string();
        let smtp_host = win.get_smtp_host().trim().to_string();
        if !email.contains('@') {
            anyhow::bail!("请填写正确的邮箱地址");
        }
        if password.is_empty() {
            anyhow::bail!("请填写密码(或服务商授权码)");
        }
        if imap_host.is_empty() || smtp_host.is_empty() {
            anyhow::bail!("请填写 IMAP / SMTP 服务器");
        }
        let imap_port: u16 = win.get_imap_port().trim().parse().context("IMAP 端口必须是数字")?;
        let smtp_port: u16 = win.get_smtp_port().trim().parse().context("SMTP 端口必须是数字")?;
        Ok(AccountConfig {
            id: uuid::Uuid::new_v4().to_string(),
            display_name: win.get_display_name().trim().to_string(),
            email,
            imap_host,
            imap_port,
            smtp_host,
            smtp_port,
            protected_password: dpapi::protect(&password)?,
        })
    }

    fn probe(cfg: &AccountConfig) -> anyhow::Result<usize> {
        let client = crate::imap::MailClient::new(cfg.clone())?;
        Ok(client.list_folders()?.len())
    }

    {
        let win_w = win.as_weak();
        win.on_test_clicked(move || {
            let Some(w) = win_w.upgrade() else { return };
            let cfg = match build_config(&w) {
                Ok(c) => c,
                Err(e) => {
                    w.set_status(e.to_string().into());
                    return;
                }
            };
            w.set_busy(true);
            w.set_status("测试中…".into());
            let win_w = win_w.clone();
            std::thread::spawn(move || {
                let result = probe(&cfg);
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(w) = win_w.upgrade() {
                        w.set_busy(false);
                        match result {
                            Ok(n) => w.set_status(format!("连接成功,发现 {n} 个文件夹。").into()),
                            Err(e) => w.set_status(format!("连接失败:{e}").into()),
                        }
                    }
                });
            });
        });
    }
    {
        let win_w = win.as_weak();
        let ui = ui.clone();
        win.on_save_clicked(move || {
            let Some(w) = win_w.upgrade() else { return };
            let cfg = match build_config(&w) {
                Ok(c) => c,
                Err(e) => {
                    w.set_status(e.to_string().into());
                    return;
                }
            };
            w.set_busy(true);
            w.set_status("验证 IMAP 连接…".into());
            let win_w = win_w.clone();
            let ui = ui.clone();
            std::thread::spawn(move || {
                let result = probe(&cfg);
                let _ = slint::invoke_from_event_loop(move || {
                    let Some(w) = win_w.upgrade() else { return };
                    match result {
                        Err(e) => {
                            w.set_busy(false);
                            w.set_status(format!("IMAP 连接失败:{e}").into());
                        }
                        Ok(_) => {
                            let idx = {
                                let mut st = ui.state.lock().unwrap();
                                if st
                                    .accounts
                                    .iter()
                                    .any(|a| a.email.eq_ignore_ascii_case(&cfg.email))
                                {
                                    w.set_busy(false);
                                    w.set_status("该邮箱账户已存在".into());
                                    return;
                                }
                                st.accounts.push(cfg.clone());
                                st.loaded_account = None;
                                st.accounts.len() - 1
                            };
                            if let Err(e) = accounts::save(&ui.state.lock().unwrap().accounts) {
                                w.set_status(format!("保存配置失败:{e}").into());
                                return;
                            }
                            let _ = w.hide();
                            let main = ui.win.clone();
                            let _ = slint::invoke_from_event_loop(move || {
                                if let Some(m) = main.upgrade() {
                                    refresh_account_model(&m, &ui.state);
                                    m.set_account_index(idx as i32);
                                }
                            });
                        }
                    }
                });
            });
        });
    }
    {
        let win_w = win.as_weak();
        win.on_cancel_clicked(move || {
            if let Some(w) = win_w.upgrade() {
                let _ = w.hide();
            }
        });
    }
    let _ = win.show();
}

use anyhow::Context as _;
