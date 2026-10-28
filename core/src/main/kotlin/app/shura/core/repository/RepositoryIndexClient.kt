package app.shura.core.repository

import java.net.HttpURLConnection

class RepositoryIndexClient(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 8_000,
    private val maxIndexBytes: Int = 2 * 1024 * 1024,
) : RepositoryIndexSource {
    init {
        require(connectTimeoutMillis > 0) { "مهلة الاتصال يجب أن تكون موجبة" }
        require(readTimeoutMillis > 0) { "مهلة القراءة يجب أن تكون موجبة" }
        require(maxIndexBytes in 1..16 * 1024 * 1024) { "حد حجم الفهرس غير صالح" }
    }

    override fun fetch(repository: RepositoryAddress): String {
        val indexUri = repository.url.resolve("index.min.json")
        require(
            indexUri.scheme == "https" &&
                indexUri.host.equals(repository.url.host, ignoreCase = true) &&
                indexUri.port == repository.url.port &&
                indexUri.path.startsWith(repository.url.path),
        ) {
            "عنوان الفهرس خارج نطاق المستودع"
        }
        val connection = indexUri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Shura/0.1 repository-catalog")
            val responseCode = connection.responseCode
            require(responseCode == HttpURLConnection.HTTP_OK) {
                "فشل جلب الفهرس: HTTP $responseCode"
            }
            require(connection.contentLengthLong in -1..maxIndexBytes.toLong()) {
                "حجم فهرس المستودع يتجاوز الحد المسموح"
            }
            return connection.inputStream.use { input ->
                val bytes = input.readNBytes(maxIndexBytes + 1)
                require(bytes.size <= maxIndexBytes) { "حجم فهرس المستودع يتجاوز الحد المسموح" }
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }
}