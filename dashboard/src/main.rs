// Spawnproof's dashboard: a window onto the machine's Spawnproof daemon. It shows the projects Spawnproof knows of, the
// queue as it changes, and past test runs with their checks and screenshots.
//
// The app is a client of the daemon, like the `spawnproof` command: it talks to the same socket, one JSON object per
// line, and asks only for what the command could. The window (ui/) is plain HTML and JavaScript; this side passes its
// requests on, follows the daemon's live status, and serves the kept runs' screenshots to it.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde_json::{json, Value};
use std::borrow::Cow;
use std::io::{self, BufRead, BufReader, Write};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};
use tauri::http::{Request, Response, StatusCode};
use tauri::{AppHandle, Emitter, Manager};

use std::os::unix::net::UnixStream;

// The daemon's socket is a Unix domain socket; Windows comes later (the roadmap's last item).
#[cfg(not(unix))]
compile_error!("The dashboard works on Linux and macOS so far.");

/// What the window may ask the daemon. Acting on things (rerunning tests, pausing the queue) comes later, and will be
/// added here one request at a time.
const ALLOWED: &[&str] = &["status", "projects", "runs", "run"];

/// Where the daemon keeps past runs, from its status: screenshots are served only from in there.
struct RunsFolder(Mutex<Option<PathBuf>>);

fn env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.trim().is_empty())
}

/// The daemon's socket, found the way the `spawnproof` command finds it (its Dirs class). `spawnproof dashboard` says
/// where it is in SPAWNPROOF_SOCKET, which also covers a SPAWNPROOF_HOME of its own.
fn socket() -> PathBuf {
    if let Some(s) = env("SPAWNPROOF_SOCKET") {
        return PathBuf::from(s);
    }
    if let Some(home) = env("SPAWNPROOF_HOME") {
        return PathBuf::from(home).join("run").join("daemon.sock");
    }
    if cfg!(target_os = "linux") {
        if let Some(runtime) = env("XDG_RUNTIME_DIR") {
            return PathBuf::from(runtime).join("spawnproof").join("daemon.sock");
        }
    }
    state().join("run").join("daemon.sock")
}

fn state() -> PathBuf {
    let home = PathBuf::from(env("HOME").unwrap_or_else(|| ".".into()));
    if cfg!(target_os = "macos") {
        return home.join("Library").join("Application Support").join("spawnproof");
    }
    env("XDG_STATE_HOME").map(PathBuf::from).unwrap_or_else(|| home.join(".local").join("state")).join("spawnproof")
}

/// A connection to the daemon, past its welcome.
struct Connection {
    reader: BufReader<UnixStream>,
    writer: UnixStream,
    /// The daemon's version, from its welcome.
    version: String,
}

fn connect(timeout: Duration) -> io::Result<Connection> {
    let stream = UnixStream::connect(socket())?;
    stream.set_read_timeout(Some(timeout))?;
    let mut c = Connection { reader: BufReader::new(stream.try_clone()?), writer: stream, version: String::new() };
    let welcome = c.read()?;
    c.version = welcome.get("version").and_then(Value::as_str).unwrap_or("?").to_string();
    Ok(c)
}

impl Connection {
    fn send(&mut self, message: &Value) -> io::Result<()> {
        let mut line = message.to_string();
        line.push('\n');
        self.writer.write_all(line.as_bytes())
    }

    fn read(&mut self) -> io::Result<Value> {
        let mut line = String::new();
        loop {
            line.clear();
            if self.reader.read_line(&mut line)? == 0 {
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Spawnproof closed the connection"));
            }
            if !line.trim().is_empty() {
                return serde_json::from_str(&line).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e));
            }
        }
    }
}

/// One request and its reply; an error reply becomes the error.
fn call(request: &Value) -> Result<Value, String> {
    let mut c = connect(Duration::from_secs(30)).map_err(|e| format!("couldn't reach Spawnproof: {e}"))?;
    c.send(request).map_err(|e| e.to_string())?;
    let reply = c.read().map_err(|e| e.to_string())?;
    if reply.get("type").and_then(Value::as_str) == Some("error") {
        return Err(reply.get("message").and_then(Value::as_str).unwrap_or("Spawnproof said no").to_string());
    }
    Ok(reply)
}

/// The window's requests to the daemon.
#[tauri::command]
async fn daemon(request: Value) -> Result<Value, String> {
    let kind = request.get("type").and_then(Value::as_str).unwrap_or_default().to_string();
    if !ALLOWED.contains(&kind.as_str()) {
        return Err(format!("the dashboard doesn't ask Spawnproof for '{kind}'"));
    }
    tauri::async_runtime::spawn_blocking(move || call(&request)).await.map_err(|e| e.to_string())?
}

/// Follows the daemon's status, a message a second, and hands each to the window as a "status" event. When the daemon
/// goes away (stopped, or replaced by a newer Spawnproof), the window hears "offline", and the daemon is started again
/// if none comes back, as a build would start it.
fn follow(app: AppHandle) {
    thread::spawn(move || {
        let mut started: Option<Instant> = None;
        let mut gone_since: Option<Instant> = None;
        loop {
            let mut message = match follow_once(&app) {
                Ok(()) => "Spawnproof stopped".to_string(),
                Err(e) if matches!(e.kind(), io::ErrorKind::NotFound | io::ErrorKind::ConnectionRefused) => "Spawnproof isn't running".to_string(),
                Err(e) if e.kind() == io::ErrorKind::Unsupported => e.to_string(),
                Err(e) => format!("Lost touch with Spawnproof ({e})"),
            };
            let since = *gone_since.get_or_insert_with(Instant::now);
            // A daemon being replaced is back within a few seconds; one that isn't is started, at most every half minute.
            // `spawnproof start` also replaces an older Spawnproof, once its games are done.
            if since.elapsed() > Duration::from_secs(3) && started.is_none_or(|t| t.elapsed() > Duration::from_secs(30)) {
                started = Some(Instant::now());
                match start_daemon() {
                    Ok(()) => message.push_str(": starting it…"),
                    Err(problem) => message.push_str(&format!(", and it couldn't be started: {problem}")),
                }
            }
            let _ = app.emit("offline", json!({ "message": message }));
            thread::sleep(Duration::from_secs(1));
            if connect(Duration::from_secs(5)).is_ok() {
                gone_since = None;
            }
        }
    });
}

fn follow_once(app: &AppHandle) -> io::Result<()> {
    // A status comes every second: one that doesn't come in 15 means the daemon is stuck.
    let mut c = connect(Duration::from_secs(15))?;
    c.send(&json!({"type": "follow"}))?;
    loop {
        let status = c.read()?;
        if status.get("type").and_then(Value::as_str) == Some("error") {
            // A Spawnproof from before the dashboard, which doesn't know "follow".
            return Err(io::Error::new(io::ErrorKind::Unsupported, format!("Spawnproof {} is running, which is older than this dashboard", c.version)));
        }
        if let Some(folder) = status.get("runsFolder").and_then(Value::as_str) {
            *app.state::<RunsFolder>().0.lock().unwrap() = Some(PathBuf::from(folder));
        }
        if app.emit("status", &status).is_err() {
            return Ok(());
        }
    }
}

/// `spawnproof start`: the command `spawnproof dashboard` passed (one argument a line), or the installed one.
fn start_daemon() -> Result<(), String> {
    let command: Vec<String> = match env("SPAWNPROOF_START") {
        Some(lines) => lines.lines().map(str::to_string).collect(),
        None => {
            let installed = env("HOME").map(|h| PathBuf::from(h).join(".local").join("bin").join("spawnproof"));
            let program = installed.filter(|p| p.is_file()).map(|p| p.to_string_lossy().into_owned()).unwrap_or_else(|| "spawnproof".into());
            vec![program, "start".into()]
        }
    };
    let (program, args) = command.split_first().ok_or("no command to start Spawnproof with")?;
    let status = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map_err(|e| format!("couldn't run {program}: {e} (is the spawnproof command installed? `spawnproof install` puts it in ~/.local/bin)"))?;
    if status.success() { Ok(()) } else { Err(format!("`spawnproof start` failed ({status}); see daemon.log in {}", state().display())) }
}

/// Serves a kept run's files to the window: shot://localhost/<run id>/<path in the run's folder>. Nothing outside the
/// runs folder the daemon named is served, whatever the address says.
fn serve_shot(app: &AppHandle, request: &Request<Vec<u8>>) -> Response<Cow<'static, [u8]>> {
    let reply = |code: StatusCode, kind: &str, body: Vec<u8>| {
        Response::builder().status(code).header("Content-Type", kind).body(Cow::Owned(body)).unwrap()
    };
    let not_found = || reply(StatusCode::NOT_FOUND, "text/plain", b"not found".to_vec());
    let Some(root) = app.state::<RunsFolder>().0.lock().unwrap().clone() else { return not_found() };
    let wanted = decode(request.uri().path().trim_start_matches('/'));
    let rel = Path::new(&wanted);
    if wanted.is_empty() || !rel.components().all(|c| matches!(c, Component::Normal(_))) {
        return not_found();
    }
    let (Ok(root), Ok(file)) = (root.canonicalize(), root.join(rel).canonicalize()) else { return not_found() };
    if !file.starts_with(&root) || !file.is_file() {
        return not_found();
    }
    let kind = match file.extension().and_then(|e| e.to_str()).map(str::to_ascii_lowercase).as_deref() {
        Some("png") => "image/png",
        Some("jpg") | Some("jpeg") => "image/jpeg",
        Some("gif") => "image/gif",
        Some("webp") => "image/webp",
        Some("txt") | Some("log") => "text/plain; charset=utf-8",
        Some("json") => "application/json",
        _ => "application/octet-stream",
    };
    match std::fs::read(&file) {
        Ok(bytes) => reply(StatusCode::OK, kind, bytes),
        Err(_) => not_found(),
    }
}

/// %20 and the like, back to what they stand for.
fn decode(path: &str) -> String {
    let bytes = path.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = |b: u8| (b as char).to_digit(16);
            if let (Some(high), Some(low)) = (hex(bytes[i + 1]), hex(bytes[i + 2])) {
                out.push((high * 16 + low) as u8);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn main() {
    tauri::Builder::default()
        .manage(RunsFolder(Mutex::new(None)))
        .register_uri_scheme_protocol("shot", |ctx, request| serve_shot(ctx.app_handle(), &request))
        .invoke_handler(tauri::generate_handler![daemon])
        .setup(|app| {
            follow(app.handle().clone());
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("the dashboard couldn't start");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_escaped_paths() {
        assert_eq!(decode("20261109-120000-000-abcd/screenshots/my%20test/front.png"), "20261109-120000-000-abcd/screenshots/my test/front.png");
        assert_eq!(decode("a%2"), "a%2");
        assert_eq!(decode("100%"), "100%");
    }
}
