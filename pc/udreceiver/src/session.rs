//! A paired session over one TLS 1.3 stream: connect, pair (6-digit compare), silent
//! reconnect, keep-alive handling, and the bench loops (RTT + throughput).

use std::fmt::Write as _;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::mpsc::{sync_channel, Receiver, RecvTimeoutError};
use std::time::{Duration, Instant};

use rustls::StreamOwned;

use crate::crypto::{cert_fingerprint, fingerprint_hex, pairing_code, PhoneCertVerifier, ReceiverIdentity, TLS_SERVER_NAME};
use crate::protocol::*;

pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
pub const PAIR_CONFIRM_TIMEOUT: Duration = Duration::from_secs(300);
pub const BENCH_RTT_SAMPLES: usize = 1000;
pub const BENCH_RTT_PAYLOAD: usize = 64;
pub const BENCH_MESSAGE_LEN: usize = 1 << 20; // 1 MiB logical messages
/// Default in-flight window. The first design used 8 MiB in flight; that deadlocks the
/// adb-forward dev path: the guest TCP queues fill (~1 MB each way) and freeze -- the
/// app blocks writing an echo while the relay blocks writing the next input (verified
/// 2026-10-18 via /proc/net/tcp on the emulator, queues identical minutes apart).
/// 256 KiB stays inside every buffer on that path. `--window-kib` overrides it.
pub const BENCH_WINDOW_BYTES: u64 = 256 << 10;
/// Keep-alive cadence (docs/PHASE4_PROTOCOL.md); the view pipeline sends its empty
/// control frame on this cadence (audit: it was hard-coded at the callsite).
pub const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(5);
pub const LINK_DEAD_TIMEOUT: Duration = Duration::from_secs(15);

/// One raw chunk of the TLS byte stream, handed from the reader thread to the TLS state
/// machine.
enum WireChunk {
    Data(Vec<u8>),
    Eof,
    Failed(std::io::Error),
}

/// Raw socket bytes arrive through this channel. The TLS state machine still drives
/// `read_tls`/`process_new_packets` (inside rustls' `StreamOwned`); it just takes its
/// transport bytes from here instead of a socket read.
///
/// The tick is what lets the pipeline wake with no inbound data (so it can flush input):
/// it waits on the *channel*, never on the socket with a timeout. A timed-out blocking
/// `recv` on a Windows socket is documented to leave the connection in an indeterminate
/// state, and this project already lost streams to timed reads (B-067, B-072); the socket
/// itself only ever sees fully blocking reads, in the reader thread.
pub struct ChunkReader {
    rx: Receiver<WireChunk>,
    cur: Vec<u8>,
    pos: usize,
    tick: Option<Duration>,
    eof: bool,
}

impl ChunkReader {
    fn new(rx: Receiver<WireChunk>) -> Self {
        Self {
            rx,
            cur: Vec::new(),
            pos: 0,
            tick: None,
            eof: false,
        }
    }

    /// `None` blocks until bytes arrive (pairing/bench, as before). `Some(d)` waits at
    /// most `d` and then returns `WouldBlock`, so the caller can do its own work.
    pub fn set_tick(&mut self, tick: Option<Duration>) {
        self.tick = tick;
    }

    /// Wait for the next wire chunk; `WouldBlock` when the tick expires first.
    fn fill(&mut self) -> std::io::Result<()> {
        loop {
            let chunk = match self.tick {
                Some(d) => match self.rx.recv_timeout(d) {
                    Ok(c) => c,
                    Err(RecvTimeoutError::Timeout) => {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::WouldBlock,
                            "no socket bytes yet",
                        ))
                    }
                    Err(RecvTimeoutError::Disconnected) => {
                        self.eof = true;
                        return Ok(());
                    }
                },
                None => match self.rx.recv() {
                    Ok(c) => c,
                    Err(_) => {
                        self.eof = true;
                        return Ok(());
                    }
                },
            };
            match chunk {
                WireChunk::Data(v) if v.is_empty() => continue,
                WireChunk::Data(v) => {
                    self.cur = v;
                    self.pos = 0;
                    return Ok(());
                }
                WireChunk::Eof => {
                    self.eof = true;
                    return Ok(());
                }
                WireChunk::Failed(e) => return Err(e),
            }
        }
    }

    fn read_into(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        loop {
            if self.pos < self.cur.len() {
                let n = (self.cur.len() - self.pos).min(buf.len());
                buf[..n].copy_from_slice(&self.cur[self.pos..self.pos + n]);
                self.pos += n;
                return Ok(n);
            }
            if self.eof {
                return Ok(0);
            }
            self.fill()?;
        }
    }
}

/// The TLS transport: the socket for writes, plus a reader thread that turns blocking
/// socket reads into channel chunks for the TLS state machine.
pub struct ChanStream {
    raw: TcpStream,
    reader: ChunkReader,
}

impl ChanStream {
    fn new(raw: TcpStream) -> std::io::Result<Self> {
        let reader = ChunkReader::new(spawn_reader(&raw)?);
        Ok(Self { raw, reader })
    }

    pub fn set_tick(&mut self, tick: Option<Duration>) {
        self.reader.set_tick(tick);
    }

    pub fn set_nodelay(&self, on: bool) -> std::io::Result<()> {
        self.raw.set_nodelay(on)
    }

    /// A clone of the underlying socket for the read watchdog / exit wake path. It is only
    /// used to shut the socket down (never to read bytes: the reader thread owns reads).
    pub fn try_clone(&self) -> std::io::Result<TcpStream> {
        self.raw.try_clone()
    }
}

impl Read for ChanStream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.reader.read_into(buf)
    }
}

impl Write for ChanStream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.raw.write(buf)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.raw.flush()
    }
}

impl Drop for ChanStream {
    fn drop(&mut self) {
        // The reader thread owns a clone and is parked in a blocking read. Shut the socket
        // down so it wakes, sends EOF and exits instead of leaking until process exit.
        let _ = self.raw.shutdown(Shutdown::Both);
    }
}

fn spawn_reader(raw: &TcpStream) -> std::io::Result<Receiver<WireChunk>> {
    let mut sock = raw.try_clone()?;
    // Small queue: enough to keep the pipe busy, small enough that a burst of video frames
    // cannot pile up hundreds of KB ahead of the frame a key just caused (measured: a 512 KB
    // queue added ~27 ms to typing while an X11 window animated; 64 KB matches the kernel
    // socket buffer the old direct-read loop was bounded by).
    let (tx, rx) = sync_channel(4);
    std::thread::Builder::new()
        .name("udrx-socket-read".into())
        .spawn(move || {
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                match sock.read(&mut buf) {
                    Ok(0) => {
                        let _ = tx.send(WireChunk::Eof);
                        break;
                    }
                    Ok(n) => {
                        if tx.send(WireChunk::Data(buf[..n].to_vec())).is_err() {
                            break;
                        }
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
                    Err(e) => {
                        let _ = tx.send(WireChunk::Failed(e));
                        break;
                    }
                }
            }
        })?;
    Ok(rx)
}

/// The TLS stream every phase uses. The socket type is the channel-backed transport, so
/// nothing above this aliases the raw socket into a timed read by mistake.
pub type TlsStream = StreamOwned<rustls::ClientConnection, ChanStream>;

pub struct Session {
    pub tls: TlsStream,
    pub phone_fp: [u8; 32],
    pub pinned: bool,
}
// phone_fp/pinned are part of the public session API; bin logs them from Phase 5.
impl Session {
    #[allow(dead_code)]
    pub fn describe(&self) -> String {
        format!(
            "phone={} pinned={}",
            &fingerprint_hex(&self.phone_fp)[..16],
            self.pinned
        )
    }
}

fn tls_connect(
    addr: &str,
    identity: &ReceiverIdentity,
) -> Result<TlsStream, String> {
    let stream = TcpStream::connect_timeout(
        &addr
            .parse()
            .map_err(|e| format!("bad --addr '{addr}': {e}"))?,
        CONNECT_TIMEOUT,
    )
    .map_err(|e| format!("tcp connect {addr}: {e}"))?;
    let _ = stream.set_nodelay(true);
    // The verifier accepts any valid self-signed phone cert; whether the phone is
    // already pinned decides silent reconnect vs the pairing step, in connect_and_pair.
    let verifier = PhoneCertVerifier::new();
    let cfg = verifier.client_config(identity);
    let name = rustls::pki_types::ServerName::try_from(TLS_SERVER_NAME.to_string())
        .map_err(|e| format!("server name: {e}"))?;
    let conn = rustls::ClientConnection::new(cfg, name)
        .map_err(|e| format!("tls client: {e}"))?;
    // StreamOwned::new is infallible; the handshake completes lazily. The transport is a
    // reader thread + channel: the socket read itself never has a timeout.
    let transport = ChanStream::new(stream).map_err(|e| format!("start socket reader: {e}"))?;
    Ok(StreamOwned::new(conn, transport))
}

/// Read the next protocol frame from the TLS stream (blocking). Frames batched in one
/// network read are held in `pending` so none is lost.
fn next_frame(
    tls: &mut TlsStream,
    dec: &mut FrameDecoder,
    pending: &mut std::collections::VecDeque<Message>,
) -> Result<Option<Message>, String> {
    if let Some(m) = pending.pop_front() {
        return Ok(Some(m));
    }
    loop {
        let mut buf = [0u8; 16384];
        let n = tls.read(&mut buf).map_err(|e| match e.kind() {
            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => {
                "stalled: no inbound data within the read tick".to_string()
            }
            _ => format!("tls read: {e}"),
        })?;
        if n == 0 {
            return Ok(None);
        }
        let msgs = dec.feed(&buf[..n]).map_err(|e| format!("protocol: {e}"))?;
        for m in msgs {
            pending.push_back(m);
        }
        if let Some(m) = pending.pop_front() {
            return Ok(Some(m));
        }
    }
}

/// Connect and pair. `label` is stored with the pin on the PC side. Returns the session
/// and a human description of what happened (paired fresh vs reconnected silently).
pub fn connect_and_pair(
    addr: &str,
    identity: &ReceiverIdentity,
    store: &crate::crypto::TrustedPhoneStore,
    label: &str,
) -> Result<(Session, String), String> {
    connect_and_pair_with(addr, identity, store, label, |_| {})
}

/// Same as [`connect_and_pair`], but hands the fresh pairing code to `on_code` as soon as
/// it exists so a GUI can show it while the phone waits for Yes. The command line passes a
/// no-op and keeps its printed code.
pub fn connect_and_pair_with<F>(
    addr: &str,
    identity: &ReceiverIdentity,
    store: &crate::crypto::TrustedPhoneStore,
    label: &str,
    on_code: F,
) -> Result<(Session, String), String>
where
    F: FnOnce(&str),
{
    let mut tls = tls_connect(addr, identity)?;

    // complete the TLS handshake explicitly so we can read the peer cert out
    tls.conn
        .complete_io(&mut tls.sock)
        .map_err(|e| format!("tls handshake: {e}"))?;
    let phone_cert = tls
        .conn
        .peer_certificates()
        .and_then(|c| c.first().cloned())
        .map(|c| c.as_ref().to_vec())
        .ok_or("phone presented no certificate")?;
    let fp = cert_fingerprint(&phone_cert);

    // A phone already in the trust store reconnects silently; any other phone (new,
    // second, or re-flashed device) goes through the visible pairing step below instead
    // of being refused -- even when other phones are pinned. The handshake above only
    // proved the peer holds a valid self-signed phone cert and its private key; the
    // trust decision is the user comparing the 6-digit code and tapping Yes on the phone.
    let already_pinned = store.find(&fp).is_some();
    let (description, session) = if already_pinned {
        // B-116(a): a local pin is not proof that the phone still trusts this PC. Wait for
        // the phone's own answer before reporting success; the GUI only starts the lock
        // after this function returns, so a forgotten PC never reaches a locked desktop.
        match wait_pinned_start(&mut tls)? {
            PhoneGreeting::Trusted => (
                format!(
                    "reconnected to phone {} ({}) - phone confirmed this PC",
                    &fingerprint_hex(&fp)[..16],
                    addr
                ),
                Session {
                    tls,
                    phone_fp: fp,
                    pinned: true,
                },
            ),
            PhoneGreeting::Pair(from_phone) => {
                let code = if from_phone.is_empty() {
                    let my_fp = identity.fingerprint();
                    pairing_code(&fp, &my_fp)
                } else {
                    from_phone
                };
                on_code(&code);
                println!("Phone forgot this PC. PC code:  {code}  <-- MUST match the code on the phone");
                println!("Waiting for Yes on the phone (up to {PAIR_CONFIRM_TIMEOUT:?}) ...");
                if !wait_pair_confirm(&mut tls)? {
                    return Err("phone answered No; nothing was stored".to_string());
                }
                store.remember(&fp, label);
                (
                    format!(
                        "re-paired with phone {} ({addr}) after the phone forgot this PC",
                        &fingerprint_hex(&fp)[..16]
                    ),
                    Session {
                        tls,
                        phone_fp: fp,
                        pinned: true,
                    },
                )
            }
            PhoneGreeting::Refused(why) => {
                let plain = if why.contains("desktop-live") {
                    "Your phone is already showing a desktop to another PC. Stop the desktop on the phone, then try again."
                } else {
                    "Your phone does not trust this PC any more - pair again"
                };
                return Err(plain.to_string());
            }
            PhoneGreeting::Other => {
                return Err("your phone did not confirm this PC; open the app and pair again"
                    .to_string())
            }
        }
    } else {
        // B-126: an unpaired PC must never print a code the phone did not show. Read
        // the phone's greeting first (10 s deadline); then act on what the phone says.
        let start = classify_unpinned_start(wait_pinned_start(&mut tls)?);
        match start {
            UnpinnedStart::RepromptForTrusted => {
                // The phone still trusts this PC, but this PC forgot the phone. Send
                // "pair-request" so the phone shows the code again and asks the user;
                // this PC must not treat its own pin as proof of the phone's trust.
                write_frame(&mut tls, CH_CONTROL, 0, b"pair-request")?;
                let code = pairing_code(&fp, &identity.fingerprint());
                on_code(&code);
                println!("PC code:  {code}  <-- MUST match the code on the phone");
                println!("This PC no longer knows this phone. Confirm the code on the phone (up to {PAIR_CONFIRM_TIMEOUT:?}) ...");
                if !wait_pair_confirm(&mut tls)? {
                    return Err("phone answered No; nothing was stored".to_string());
                }
                store.remember(&fp, label);
                println!("Phone confirmed. Pin saved to {}", store.path().display());
                (
                    format!(
                        "paired again with phone {} ({addr}) - the phone still trusted this PC",
                        &fingerprint_hex(&fp)[..16]
                    ),
                    Session {
                        tls,
                        phone_fp: fp,
                        pinned: true,
                    },
                )
            }
            UnpinnedStart::PairWithPhoneCode(from_phone) => {
                let code = if from_phone.is_empty() {
                    pairing_code(&fp, &identity.fingerprint())
                } else {
                    from_phone
                };
                on_code(&code);
                println!("PC code:  {code}  <-- MUST match the code on the phone");
                println!("Waiting for Yes on the phone (up to {PAIR_CONFIRM_TIMEOUT:?}) ...");
                if !wait_pair_confirm(&mut tls)? {
                    return Err("phone answered No; nothing was stored".to_string());
                }
                store.remember(&fp, label);
                println!("Phone confirmed. Pin saved to {}", store.path().display());
                (
                    format!("paired with phone {} ({addr})", &fingerprint_hex(&fp)[..16]),
                    Session {
                        tls,
                        phone_fp: fp,
                        pinned: true,
                    },
                )
            }
            UnpinnedStart::Refuse(reason) => return Err(reason),
        }
    };
    Ok((session, description))
}

/// Pump control/keep-alive frames until a pair-ok or pair-no arrives on the control
/// channel; echo keep-alive requests while waiting. The phone sends its own control
/// keep-alives, so a frame (and the deadline check) returns at least every few seconds.
fn wait_pair_confirm(tls: &mut TlsStream) -> Result<bool, String> {
    // Tick the channel wait so the deadline below is enforced even when the phone sends
    // nothing while it waits for the user (the phone's pump suspends on the decision).
    // Restored to blocking afterwards: the bench path manages its own reads. The socket
    // itself is never given a read timeout (see ChunkReader).
    tls.get_mut().set_tick(Some(Duration::from_millis(500)));
    let r = wait_pair_confirm_inner(tls);
    tls.get_mut().set_tick(None);
    r
}

fn is_read_tick(e: &str) -> bool {
    e.starts_with("stalled:")
}

fn wait_pair_confirm_inner(tls: &mut TlsStream) -> Result<bool, String> {
    let deadline = Instant::now() + PAIR_CONFIRM_TIMEOUT;
    let mut dec = FrameDecoder::new();
    let mut pending = std::collections::VecDeque::new();
    loop {
        if Instant::now() > deadline {
            return Err("pair confirm timed out".to_string());
        }
        match next_frame(tls, &mut dec, &mut pending) {
            Ok(Some(m)) => {
                if m.channel == CH_CONTROL {
                    if m.payload == b"pair-ok" {
                        return Ok(true);
                    }
                    if m.payload == b"pair-no" {
                        return Ok(false);
                    }
                    if m.payload.is_empty() {
                        echo_keepalive(tls);
                    }
                }
            }
            Ok(None) => return Err("phone closed the connection while pairing".into()),
            Err(e) if is_read_tick(&e) => continue, // no frame yet; deadline checked above
            Err(e) => return Err(e),
        }
    }
}

fn echo_keepalive(tls: &mut TlsStream) {
    let _ = write_frame(tls, CH_CONTROL, FLAG_KEEPALIVE_ECHO, &[]);
}

/// B-116(a): what the phone says first on a session. A pin in our store only says that WE
/// trusted the phone once; it does not prove the phone still trusts US. The phone answers
/// `trusted` for a live pin, `pair <code>` when it has forgotten us, or `refused <why>`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PhoneGreeting {
    Trusted,
    Pair(String),
    Refused(String),
    Other,
}

/// Pure state decision, unit-tested: classify the phone's first control line.
pub fn classify_phone_greeting(text: &str) -> PhoneGreeting {
    let t = text.trim();
    if t == "trusted" {
        return PhoneGreeting::Trusted;
    }
    if let Some(rest) = t.strip_prefix("pair") {
        return PhoneGreeting::Pair(rest.trim().to_string());
    }
    if let Some(rest) = t.strip_prefix("refused") {
        return PhoneGreeting::Refused(rest.trim().to_string());
    }
    PhoneGreeting::Other
}

/// B-126: what an unpaired PC does with the phone's greeting. A local pin is not proof
/// the phone still trusts us, so even a `Trusted` answer means "ask the phone to show
/// the code again and have the user confirm". Pure so the whole decision is unit-tested.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UnpinnedStart {
    RepromptForTrusted,
    PairWithPhoneCode(String),
    Refuse(String),
}

/// Classify the phone's opening greeting for an unpaired (unpinned) PC. Trusted -> ask
/// the phone again; Pair(c) -> reuse the phone's code; Refused/Other -> refuse plainly.
pub fn classify_unpinned_start(g: PhoneGreeting) -> UnpinnedStart {
    match g {
        PhoneGreeting::Trusted => UnpinnedStart::RepromptForTrusted,
        PhoneGreeting::Pair(c) => UnpinnedStart::PairWithPhoneCode(c),
        PhoneGreeting::Refused(why) => {
            let plain = if why.contains("desktop-live") {
                "Your phone is already showing a desktop to another PC. Stop the desktop on the phone, then try again."
            } else {
                "Your phone does not trust this PC any more - pair again"
            };
            UnpinnedStart::Refuse(plain.to_string())
        }
        PhoneGreeting::Other => UnpinnedStart::Refuse(
            "your phone did not confirm this PC; open the app and pair again".to_string(),
        ),
    }
}

/// B-116(b): reconnect backoff. 0.5 s, 1 s, 2 s, 4 s, then 8/10 s capped at 10 s.
/// Pure so the sequence is unit-tested without sleeping.
pub fn reconnect_delay_ms(attempt: u32) -> u64 {
    let ms = 500u64.saturating_mul(1u64 << attempt.min(5));
    ms.min(10_000)
}

/// B-116(b): errors that retrying cannot fix. A refused/forgotten phone or a phone that is
/// already showing a desktop needs the user, so the reconnect loop must give up with the
/// plain message instead of hammering it forever. Pure and unit-tested.
pub fn terminal_connect_error(e: &str) -> bool {
    e.contains("does not trust")
        || e.contains("already showing a desktop")
        || e.contains("phone answered No")
        || e.contains("pair again")
}

/// B-116(a): wait for the phone's opening control line on a pinned reconnect. This runs
/// BEFORE the caller reports success, so the GUI cannot start the lock on one-sided trust.
fn wait_pinned_start(tls: &mut TlsStream) -> Result<PhoneGreeting, String> {
    let deadline = Instant::now() + Duration::from_secs(10);
    tls.get_mut().set_tick(Some(Duration::from_millis(500)));
    let r = wait_pinned_start_inner(tls, deadline);
    tls.get_mut().set_tick(None);
    r
}

fn wait_pinned_start_inner(tls: &mut TlsStream, deadline: Instant) -> Result<PhoneGreeting, String> {
    let mut dec = FrameDecoder::new();
    let mut pending = std::collections::VecDeque::new();
    loop {
        if Instant::now() > deadline {
            return Err("your phone did not confirm this PC; open the app and pair again".into());
        }
        match next_frame(tls, &mut dec, &mut pending) {
            Ok(Some(m)) if m.channel == CH_CONTROL => {
                if m.payload.is_empty() {
                    echo_keepalive(tls);
                    continue;
                }
                let greeting = classify_phone_greeting(&String::from_utf8_lossy(&m.payload));
                match greeting {
                    PhoneGreeting::Other => continue,
                    other => return Ok(other),
                }
            }
            Ok(Some(_)) => continue,
            Ok(None) => {
                return Err("your phone closed the link before confirming this PC; pair again".into())
            }
            Err(e) if is_read_tick(&e) => continue,
            Err(e) => return Err(e),
        }
    }
}

// ------------------------------------------------------------------------- bench

#[derive(Debug, Clone, Copy)]
pub struct Stats {
    pub p50_ms: f64,
    pub p95_ms: f64,
    pub p99_ms: f64,
    pub min_ms: f64,
    pub max_ms: f64,
}

pub fn percentiles(mut samples_ms: Vec<f64>) -> Stats {
    if samples_ms.is_empty() {
        return Stats {
            p50_ms: 0.0,
            p95_ms: 0.0,
            p99_ms: 0.0,
            min_ms: 0.0,
            max_ms: 0.0,
        };
    }
    samples_ms.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let at = |frac: f64| -> f64 {
        let idx = ((samples_ms.len() as f64) * frac).round() as usize;
        samples_ms[idx.min(samples_ms.len() - 1)]
    };
    Stats {
        p50_ms: at(0.50),
        p95_ms: at(0.95),
        p99_ms: at(0.99),
        min_ms: samples_ms[0],
        max_ms: samples_ms[samples_ms.len() - 1],
    }
}

pub struct BenchResult {
    pub rtt: Stats,
    pub mb_per_s: f64,
    pub seconds: u64,
    pub echoed_bytes: u64,
}

impl BenchResult {
    pub fn render(&self, link_desc: &str) -> String {
        let mut s = String::new();
        let _ = writeln!(s, "=== BENCH ({link_desc}) ===");
        let _ = writeln!(
            s,
            "RTT ({} samples, {} B per sample): p50 {:.2} ms | p95 {:.2} ms | p99 {:.2} ms | min {:.2} | max {:.2}",
            BENCH_RTT_SAMPLES, BENCH_RTT_PAYLOAD, self.rtt.p50_ms, self.rtt.p95_ms, self.rtt.p99_ms, self.rtt.min_ms, self.rtt.max_ms
        );
        let _ = writeln!(
            s,
            "Throughput: {:.2} MB/s over {} s ({} MB echoed)",
            self.mb_per_s,
            self.seconds,
            self.echoed_bytes / (1024 * 1024)
        );
        s
    }
}

pub fn bench(
    session: &mut Session,
    seconds: u64,
    window_bytes: u64,
) -> Result<BenchResult, String> {
    // ---- RTT: 1000 small pings, echo per frame
    let mut samples = Vec::with_capacity(BENCH_RTT_SAMPLES);
    {
        let tls = &mut session.tls;
        let mut dec = FrameDecoder::new();
        let mut pending = std::collections::VecDeque::new();
        for _ in 0..BENCH_RTT_SAMPLES {
            let payload = vec![0u8; BENCH_RTT_PAYLOAD];
            let t0 = Instant::now();
            write_frame(tls, CH_BENCH, 0, &payload)?;
            // read until the echo arrives (skip keep-alive/controls)
            loop {
                let m = next_frame(tls, &mut dec, &mut pending)?
                    .ok_or("phone closed during RTT bench")?;
                if m.channel == CH_BENCH && m.payload.len() == BENCH_RTT_PAYLOAD {
                    break;
                }
                if m.channel == CH_CONTROL && m.payload.is_empty() {
                    echo_keepalive(tls);
                }
            }
            samples.push(t0.elapsed().as_secs_f64() * 1000.0);
        }
    }
    let rtt = percentiles(samples);

    // ---- Throughput: 1 MiB logical messages, chunked to 64 KiB by the writer,
    //      chunk-echoed by the phone, window-limited so the phone can keep up.
    let deadline = Instant::now() + Duration::from_secs(seconds);
    let mut echoed_bytes: u64 = 0;
    let mut dec = FrameDecoder::new();
    let mut pending = std::collections::VecDeque::new();
    {
        let tls = &mut session.tls;
        let send_start = Instant::now();
        let mut in_flight: u64 = 0;
        let mut seq: u64 = 0;
        loop {
            let now = Instant::now();
            if now >= deadline && in_flight == 0 {
                break;
            }
            if in_flight < window_bytes && now < deadline {
                let payload = message_for(seq);
                for chunk in payload.chunks(MAX_FRAME_PAYLOAD) {
                    let frame = encode_frame(CH_BENCH, 0, chunk).map_err(|e| e.to_string())?;
                    tls.write_all(&frame).map_err(|e| format!("bench write: {e}"))?;
                    in_flight += chunk.len() as u64;
                }
                tls.flush().map_err(|e| format!("bench flush: {e}"))?;
                seq += 1;
                continue; // keep pumping before reading (window allows pipelining)
            }
            if in_flight > 0 {
                let m = next_frame(tls, &mut dec, &mut pending)?
                    .ok_or("phone closed during throughput")?;
                if m.channel == CH_BENCH {
                    echoed_bytes += m.payload.len() as u64;
                    in_flight -= m.payload.len() as u64;
                } else if m.channel == CH_CONTROL && m.payload.is_empty() {
                    echo_keepalive(tls);
                }
                continue;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        let _ = tls.flush();
        let elapsed = send_start.elapsed().as_secs_f64().max(0.001);
        Ok(BenchResult {
            rtt,
            mb_per_s: (echoed_bytes as f64 / (1024.0 * 1024.0)) / elapsed,
            seconds,
            echoed_bytes,
        })
    }
}

fn message_for(seq: u64) -> Vec<u8> {
    // deterministic pattern to allow corruption checks later if needed
    let mut v = Vec::with_capacity(BENCH_MESSAGE_LEN);
    for i in 0..BENCH_MESSAGE_LEN {
        let b = ((seq as usize).wrapping_mul(0x9E3779B9) ^ i) as u8;
        v.push(b);
    }
    v
}

fn write_frame(
    tls: &mut TlsStream,
    channel: u8,
    flags: u8,
    payload: &[u8],
) -> Result<(), String> {
    let frame = encode_frame(channel, flags, payload).map_err(|e| e.to_string())?;
    tls.write_all(&frame).map_err(|e| format!("write: {e}"))?;
    tls.flush().map_err(|e| format!("flush: {e}"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percentiles_basic() {
        let s: Vec<f64> = (0..100).map(|x| x as f64).collect();
        let st = percentiles(s);
        assert_eq!(st.p50_ms, 50.0);
        assert_eq!(st.p95_ms, 95.0);
        assert_eq!(st.p99_ms, 99.0);
        assert_eq!(st.min_ms, 0.0);
        assert_eq!(st.max_ms, 99.0);
    }

    #[test]
    fn percentiles_empty_and_single() {
        let st = percentiles(Vec::new());
        assert_eq!(st.p50_ms, 0.0);
        let st1 = percentiles(vec![7.5]);
        assert_eq!(st1.p50_ms, 7.5);
        assert_eq!(st1.p99_ms, 7.5);
    }

    #[test]
    fn bench_result_render_has_numbers() {
        let r = BenchResult {
            rtt: percentiles(vec![1.0, 2.0]),
            mb_per_s: 42.5,
            seconds: 5,
            echoed_bytes: 5_000,
        };
        let s = r.render("unit-test");
        assert!(s.contains("p50"));
        assert!(s.contains("42.50"));
    }

    #[test]
    fn phone_greeting_decides_trust_pair_and_refusal() {
        // B-116(a): a local pin is not enough; these are the phone's opening answers.
        assert_eq!(classify_phone_greeting("trusted"), PhoneGreeting::Trusted);
        assert_eq!(
            classify_phone_greeting("pair 927361"),
            PhoneGreeting::Pair("927361".into())
        );
        assert_eq!(
            classify_phone_greeting("  refused desktop-live \n"),
            PhoneGreeting::Refused("desktop-live".into())
        );
        assert_eq!(classify_phone_greeting("video-on"), PhoneGreeting::Other);
        assert_eq!(classify_phone_greeting(""), PhoneGreeting::Other);
    }

    #[test]
    fn unpinned_start_classifies_all_four_greetings() {
        // B-126: Trusted -> re-prompt (NOT silent acceptance), Pair -> phone's code,
        // Refused -> plain mapping, Other -> the timeout message.
        assert_eq!(
            classify_unpinned_start(PhoneGreeting::Trusted),
            UnpinnedStart::RepromptForTrusted
        );
        assert_eq!(
            classify_unpinned_start(PhoneGreeting::Pair("927361".into())),
            UnpinnedStart::PairWithPhoneCode("927361".into())
        );
        assert_eq!(
            classify_unpinned_start(PhoneGreeting::Refused("desktop-live".into())),
            UnpinnedStart::Refuse(
                "Your phone is already showing a desktop to another PC. Stop the desktop on the phone, then try again.".into()
            )
        );
        assert_eq!(
            classify_unpinned_start(PhoneGreeting::Refused("nope".into())),
            UnpinnedStart::Refuse("Your phone does not trust this PC any more - pair again".into())
        );
        assert_eq!(
            classify_unpinned_start(PhoneGreeting::Other),
            UnpinnedStart::Refuse("your phone did not confirm this PC; open the app and pair again".into())
        );
    }

    #[test]
    fn trusted_greeting_maps_to_reprompt_not_silent_acceptance() {
        // B-126 regression guard: a Trusted greeting must NOT silently reconnect a PC that
        // forgot the phone; it must ask the phone to re-pair visibly.
        assert!(matches!(
            classify_unpinned_start(PhoneGreeting::Trusted),
            UnpinnedStart::RepromptForTrusted
        ));
    }

    #[test]
    fn reconnect_backoff_starts_fast_then_caps_at_ten_seconds() {
        // B-116(b): 0.5, 1, 2, 4, 8, 10, 10 ... seconds.
        assert_eq!(reconnect_delay_ms(0), 500);
        assert_eq!(reconnect_delay_ms(1), 1_000);
        assert_eq!(reconnect_delay_ms(2), 2_000);
        assert_eq!(reconnect_delay_ms(3), 4_000);
        assert_eq!(reconnect_delay_ms(4), 8_000);
        assert_eq!(reconnect_delay_ms(5), 10_000);
        assert_eq!(reconnect_delay_ms(50), 10_000);
    }

    #[test]
    fn terminal_connect_errors_stop_the_loop_but_network_errors_retry() {
        assert!(terminal_connect_error(
            "Your phone does not trust this PC any more - pair again"
        ));
        assert!(terminal_connect_error(
            "Your phone is already showing a desktop to another PC. Stop the desktop on the phone, then try again."
        ));
        assert!(terminal_connect_error("phone answered No; nothing was stored"));
        assert!(!terminal_connect_error("connect: connection refused"));
        assert!(!terminal_connect_error("tls handshake: timed out"));
    }

    #[test]
    fn read_tick_is_not_a_pair_failure() {
        // wait_pair_confirm sets a 500 ms channel tick so its 300 s deadline is enforced
        // even while the phone shows the prompt and sends nothing: a read tick must
        // continue the loop, any other error must end it.
        assert!(is_read_tick("stalled: no inbound data within the read tick"));
        assert!(!is_read_tick("tls read: connection reset"));
        assert!(!is_read_tick("protocol: bad frame"));
    }

    fn chunk_reader(
        tick: Option<Duration>,
    ) -> (std::sync::mpsc::SyncSender<WireChunk>, ChunkReader) {
        let (tx, rx) = sync_channel(4);
        let mut r = ChunkReader::new(rx);
        r.set_tick(tick);
        (tx, r)
    }

    /// The Phase 13 correction: with no bytes the pipeline wakes on the channel tick and
    /// gets WouldBlock. It never waits on the socket with a timeout (B-067/B-072).
    #[test]
    fn no_bytes_yet_returns_would_block_within_the_tick() {
        let (_tx, mut r) = chunk_reader(Some(Duration::from_millis(5)));
        let started = Instant::now();
        let mut one = [0u8; 1];
        let e = r.read_into(&mut one).expect_err("empty reader must not block");
        assert_eq!(e.kind(), std::io::ErrorKind::WouldBlock);
        assert!(
            started.elapsed() >= Duration::from_millis(4),
            "returned before the tick: {:?}",
            started.elapsed()
        );
        assert!(
            started.elapsed() < Duration::from_millis(500),
            "tick did not bound the wait: {:?}",
            started.elapsed()
        );
    }

    #[test]
    fn chunks_are_returned_in_order_and_the_tick_only_applies_when_empty() {
        let (tx, mut r) = chunk_reader(Some(Duration::from_millis(50)));
        tx.send(WireChunk::Data(b"hello ".to_vec())).unwrap();
        tx.send(WireChunk::Data(b"world".to_vec())).unwrap();
        let mut buf = [0u8; 32];
        assert_eq!(r.read_into(&mut buf).unwrap(), 6);
        assert_eq!(&buf[..6], b"hello ");
        assert_eq!(r.read_into(&mut buf).unwrap(), 5);
        assert_eq!(&buf[..5], b"world");
        let e = r.read_into(&mut buf).expect_err("then empty");
        assert_eq!(e.kind(), std::io::ErrorKind::WouldBlock);
    }

    /// Pairing and bench run with no tick: a read must wait for the reader thread rather
    /// than report a spurious timeout.
    #[test]
    fn blocking_mode_waits_for_the_reader_thread() {
        let (tx, mut r) = chunk_reader(None);
        let sender = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(30));
            tx.send(WireChunk::Data(b"late".to_vec())).unwrap();
        });
        let mut buf = [0u8; 8];
        assert_eq!(r.read_into(&mut buf).unwrap(), 4);
        assert_eq!(&buf[..4], b"late");
        sender.join().unwrap();
    }

    #[test]
    fn eof_reads_zero_and_a_reader_error_is_passed_through() {
        let (tx, mut r) = chunk_reader(Some(Duration::from_millis(50)));
        tx.send(WireChunk::Eof).unwrap();
        let mut one = [0u8; 1];
        assert_eq!(r.read_into(&mut one).unwrap(), 0);

        let (tx2, mut r2) = chunk_reader(Some(Duration::from_millis(50)));
        tx2.send(WireChunk::Failed(std::io::Error::from(
            std::io::ErrorKind::ConnectionReset,
        )))
        .unwrap();
        let e = r2.read_into(&mut one).expect_err("error must surface");
        assert_eq!(e.kind(), std::io::ErrorKind::ConnectionReset);
    }
}
