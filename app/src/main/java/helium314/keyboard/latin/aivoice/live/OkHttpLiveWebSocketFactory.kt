// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.live

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Production boundary. Redirects are disabled and the protocol supplies the only allowed host. */
class OkHttpLiveWebSocketFactory(client: OkHttpClient) : LiveWebSocketFactory {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun open(url: String, listener: LiveWebSocketListener): LiveWebSocket {
        lateinit var adapter: Adapter
        adapter = Adapter(listener)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), adapter)
        return OkHttpSocket(socket)
    }

    private class Adapter(private val target: LiveWebSocketListener) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = target.onOpen(OkHttpSocket(webSocket))
        override fun onMessage(webSocket: WebSocket, text: String) = target.onMessage(text)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = target.onClosing(code, reason)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = target.onClosed(code, reason)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = target.onFailure()
    }

    private class OkHttpSocket(private val delegate: WebSocket) : LiveWebSocket {
        override fun send(text: String) = delegate.send(text)
        override fun close(code: Int, reason: String?) = delegate.close(code, reason)
        override fun cancel() = delegate.cancel()
        override fun toString() = "LiveWebSocket(<redacted>)"
    }
}
