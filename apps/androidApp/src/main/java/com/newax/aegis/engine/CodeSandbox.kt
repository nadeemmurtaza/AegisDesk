package com.newax.aegis.engine

import android.content.Context as AndroidContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.File
import java.net.InetAddress
import java.net.URL

/**
 * A custom console object to intercept console.log calls from JS
 */
class JsConsole {
    val logs = StringBuilder()
    fun log(vararg args: Any?) {
        logs.append(args.joinToString(" ")).append("\n")
    }
}

/**
 * A native Java helper to give the JS Sandbox HTTP capabilities,
 * specifically for downloading AI generated images. Restricted to https,
 * blocks loopback/private/link-local hosts (SSRF guard), and confines
 * output to a fixed app-private directory with a sanitized filename
 * (path traversal guard).
 */
class HttpHelper(private val outputDir: File) {
    fun downloadImage(urlString: String, filename: String): String {
        return try {
            val url = URL(urlString)
            require(url.protocol == "https") { "Only https URLs are allowed." }

            val addr = InetAddress.getAllByName(url.host)
            require(addr.none { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }) {
                "Refusing to fetch from a private/local address."
            }

            val safeName = File(filename).name.let { if (it.isBlank() || it == "." || it == "..") "image.jpg" else it }
            outputDir.mkdirs()
            val file = File(outputDir, safeName)
            require(file.canonicalPath.startsWith(outputDir.canonicalPath + File.separator)) { "Invalid filename." }

            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.inputStream.use { input ->
                java.io.FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            "Error downloading image: ${e.message}"
        }
    }
}

/**
 * Executes JavaScript code safely offline using Mozilla Rhino.
 */
object CodeSandbox {

    fun executeJs(code: String, context: AndroidContext): String {
        val rhino = Context.enter()
        rhino.optimizationLevel = -1 // Required for Android (no bytecode generation)

        return try {
            val scope: Scriptable = rhino.initSafeStandardObjects()

            // Inject our custom console
            val jsConsole = JsConsole()
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "console", Context.javaToJS(jsConsole, scope))

            // Inject our HTTP Helper
            val httpHelper = HttpHelper(File(context.cacheDir, "sandbox_downloads"))
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "http", Context.javaToJS(httpHelper, scope))

            val result = rhino.evaluateString(scope, code, "JavaScript", 1, null)
            val evalResult = Context.toString(result)
            
            if (jsConsole.logs.isNotEmpty()) {
                "Console Output:\n${jsConsole.logs}\nReturn Value: $evalResult"
            } else {
                "Return Value: $evalResult"
            }
        } catch (e: Exception) {
            "Execution Error: ${e.message}"
        } finally {
            Context.exit()
        }
    }
}
