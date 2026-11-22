package com.example.phonemirror

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.flush
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeString
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WebServer(
    private val token: String,
    private val screenFrames: MutableSharedFlow<ByteArray>,
    private val cameraFrames: MutableSharedFlow<ByteArray>,
    private val soundPlayer: SoundPlayer,
    private val onStop: () -> Unit
) {
    private var engine: ApplicationEngine? = null
    private val clients = mutableSetOf<WebSocketSession>()
    private val mutex = Mutex()

    fun start(port: Int = 8080) {
        engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(WebSockets) { pingInterval = 20_000 }
            routing {
                get("/") {
                    call.respondText(indexHtml(), ContentType.Text.Html)
                }
                mjpeg("/screen.mjpg", screenFrames)
                mjpeg("/camera.mjpg", cameraFrames)

                webSocket("/ws") {
                    if (call.request.queryParameters["token"] != token) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "bad token"))
                        return@webSocket
                    }
                    mutex.withLock { clients.add(this) }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            when {
                                text.contains("\"cmd\":\"play\"") -> {
                                    Regex("\"sound\":\"([^\"]+)\"")
                                        .find(text)?.groupValues?.get(1)?.let { soundPlayer.play(it) }
                                }
                                text.contains("\"cmd\":\"list\"") -> {
                                    val names = SoundPlayer.NAMES
                                        .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
                                    send(Frame.Text("{\"type\":\"sounds\",\"sounds\":$names}"))
                                }
                                text.contains("\"cmd\":\"stop\"") -> onStop()
                            }
                        }
                    } finally {
                        mutex.withLock { clients.remove(this) }
                    }
                }
            }
        }.start(wait = false)
    }

    suspend fun sendPcm(pcm: ByteArray) {
        val frame = Frame.Binary(true, byteArrayOf(1) + pcm)
        val snapshot = mutex.withLock { clients.toList() }
        snapshot.forEach { session ->
            try {
                session.send(frame)
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }

    private fun Route.mjpeg(path: String, frames: MutableSharedFlow<ByteArray>) {
        get(path) {
            if (call.request.queryParameters["token"] != token) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            call.response.header("Cache-Control", "no-cache")
            call.respondBytesWriter(
                contentType = ContentType.parse("multipart/x-mixed-replace; boundary=frame")
            ) {
                frames.collect { jpeg ->
                    writeString("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n")
                    writeFully(jpeg)
                    writeString("\r\n")
                    flush()
                }
            }
        }
    }

    private fun indexHtml(): String = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<title>PhoneMirror LAN</title>
<style>
  body { font-family: sans-serif; margin: 16px; }
  .row { display: flex; gap: 12px; flex-wrap: wrap; }
  img { width: 360px; border: 1px solid #ccc; background: #000; }
  button { padding: 8px 12px; margin: 2px; }
</style>
</head>
<body>
<h2>PhoneMirror LAN</h2>
<p>Экран, камера, микрофон и звуки телефона. Только в этой Wi‑Fi сети.</p>
<div class="row">
  <div><h3>Экран</h3><img src="/screen.mjpg?token=$token"></div>
  <div><h3>Камера</h3><img src="/camera.mjpg?token=$token"></div>
</div>
<h3>Микрофон</h3>
<button onclick="enableAudio()">Включить звук в браузере</button>
<p id="micStatus">Нажмите кнопку выше, чтобы услышать микрофон.</p>
<h3>Звуки на телефоне</h3>
<div id="sounds"></div>
<p><button onclick="sendCmd('stop')">Остановить трансляцию</button></p>
<script>
const TOKEN = "$token";
let ctx = null;
const ws = new WebSocket("ws://" + location.host + "/ws?token=" + TOKEN);
ws.binaryType = "arraybuffer";
ws.onopen = () => ws.send(JSON.stringify({cmd:"list"}));
ws.onmessage = (e) => {
  if (typeof e.data === "string") {
    const msg = JSON.parse(e.data);
    if (msg.type === "sounds") {
      const box = document.getElementById("sounds");
      box.innerHTML = "";
      msg.sounds.forEach(function(name){
        const b = document.createElement("button");
        b.textContent = name;
        b.onclick = () => ws.send(JSON.stringify({cmd:"play", sound:name}));
        box.appendChild(b);
      });
    }
    return;
  }
  const bytes = new Uint8Array(e.data);
  if (bytes[0] === 1) playPcm(bytes.subarray(1));
};
function enableAudio(){
  if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)({sampleRate:16000});
  ctx.resume().then(() => {
    document.getElementById("micStatus").textContent = "Микрофон активен.";
  });
}
function playPcm(bytes){
  if (!ctx) return;
  const buffer = ctx.createBuffer(1, Math.floor(bytes.length / 2), 16000);
  const channel = buffer.getChannelData(0);
  for (let i = 0; i < channel.length; i++) {
    let s = bytes[i*2] | (bytes[i*2+1] << 8);
    if (s > 32767) s -= 65536;
    channel[i] = s / 32768;
  }
  const src = ctx.createBufferSource();
  src.buffer = buffer;
  src.connect(ctx.destination);
  src.start();
}
function sendCmd(cmd){ ws.send(JSON.stringify({cmd:cmd})); }
</script>
</body>
</html>
""".trimIndent()
}
