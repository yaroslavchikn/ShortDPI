package com.example.dpibypass

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class DohResolver {

    private data class CacheEntry(
        val ip: String,
        val expires: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun resolve(host: String): String? {
        if (host.isEmpty()) return null

        // Already IPv4.
        if (host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
            return host
        }

        // Very rough IPv6 literal passthrough.
        if (host.contains(":")) {
            return host
        }

        val cached = cache[host]
        if (cached != null && cached.expires > System.currentTimeMillis()) {
            return cached.ip
        }

        var ip = queryJson(host, "https://cloudflare-dns.com/dns-query")

        if (ip == null) {
            ip = queryJson(host, "https://dns.google/resolve")
        }

        if (ip == null) {
            ip = systemResolve(host)
        }

        if (ip != null) {
            cache[host] = CacheEntry(
                ip = ip,
                expires = System.currentTimeMillis() + 5 * 60 * 1000
            )
        }

        return ip
    }

    private fun queryJson(host: String, baseUrl: String): String? {
        return try {
            val encodedHost = URLEncoder.encode(host, "UTF-8")
            val url = URL("$baseUrl?name=$encodedHost&type=A")

            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            connection.setRequestProperty("accept", "application/dns-json")

            val text = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(text)
            val answers = json.optJSONArray("Answer") ?: return null

            for (i in 0 until answers.length()) {
                val record = answers.getJSONObject(i)

                // type 1 = A record
                if (record.optInt("type", 0) == 1) {
                    val data = record.optString("data", "")
                    if (data.isNotEmpty()) {
                        return data
                    }
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun systemResolve(host: String): String? {
        return try {
            InetAddress.getByName(host).hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
