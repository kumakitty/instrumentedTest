package com.example.androidinstrumentedtest

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream

object OutputDirectoryManager {
    const val PREF_NAME = "KeyboardEvaluatorPrefs"
    const val KEY_OUTPUT_TREE_URI = "output_tree_uri"
    const val KEY_OUTPUT_TREE_LABEL = "output_tree_label"
    const val DEFAULT_PUBLIC_LABEL = "Documents/InstrumentedTest"

    private const val TAG = "OutputDirectoryManager"

    fun saveAuthorizedTreeUri(context: Context, treeUri: Uri) {
        val label = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: treeUri.lastPathSegment
            ?: treeUri.toString()
        prefs(context).edit()
            .putString(KEY_OUTPUT_TREE_URI, treeUri.toString())
            .putString(KEY_OUTPUT_TREE_LABEL, label)
            .apply()
    }

    fun getAuthorizedTreeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_OUTPUT_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun getAuthorizedDirectoryLabel(context: Context): String? {
        return prefs(context).getString(KEY_OUTPUT_TREE_LABEL, null)
    }

    fun getAuthorizedRoot(context: Context): DocumentFile? {
        val uri = getAuthorizedTreeUri(context) ?: return null
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.canWrite() }
    }

    fun hasAuthorizedDirectory(context: Context): Boolean = getAuthorizedRoot(context) != null

    fun buildStatusText(context: Context): String {
        val root = getAuthorizedRoot(context)
        return if (root != null) {
            val label = getAuthorizedDirectoryLabel(context) ?: root.name ?: "已授权目录"
            "测试输出目录: $label"
        } else {
            "测试输出目录: 未授权（将回退到 Documents/InstrumentedTest）"
        }
    }

    fun clearAuthorizedOutput(context: Context): Boolean {
        val root = getAuthorizedRoot(context) ?: return false
        return runCatching {
            deleteChildren(root)
            true
        }.onFailure {
            Log.w(TAG, "clearAuthorizedOutput failed: ${it.message}")
        }.getOrDefault(false)
    }

    fun writeText(context: Context, relativePath: String, content: String): Boolean {
        return writeBytes(context, relativePath, "text/plain", content.toByteArray(Charsets.UTF_8))
    }

    fun writeBitmapPng(context: Context, relativePath: String, bitmap: Bitmap): Boolean {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return writeBytes(context, relativePath, "image/png", bytes)
    }

    fun writeBytes(context: Context, relativePath: String, mimeType: String, data: ByteArray): Boolean {
        val root = getAuthorizedRoot(context) ?: return false
        val normalized = normalizeRelativePath(relativePath)
        if (normalized.isEmpty()) return false
        val segments = normalized.split('/').filter { it.isNotBlank() }
        val fileName = segments.lastOrNull() ?: return false
        val parent = ensureDirectories(root, segments.dropLast(1)) ?: return false
        val file = createOrReplaceFile(parent, fileName, mimeType) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(file.uri, "w")?.use { out ->
                out.write(data)
                out.flush()
            } != null
        }.onFailure {
            Log.w(TAG, "writeBytes failed for $relativePath: ${it.message}")
        }.getOrDefault(false)
    }

    fun listAuthorizedOutputFiles(context: Context): List<String> {
        val root = getAuthorizedRoot(context) ?: return emptyList()
        val results = mutableListOf<String>()
        collectPaths(root, "", results)
        return results.sorted()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun normalizeRelativePath(relativePath: String): String {
        return relativePath.replace('\\', '/').trim().trimStart('/')
    }

    private fun ensureDirectories(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = when {
                existing != null && existing.isDirectory -> existing
                existing != null -> {
                    existing.delete()
                    current.createDirectory(segment)
                }
                else -> current.createDirectory(segment)
            } ?: return null
        }
        return current
    }

    private fun createOrReplaceFile(parent: DocumentFile, fileName: String, mimeType: String): DocumentFile? {
        parent.findFile(fileName)?.delete()
        return parent.createFile(mimeType, fileName)
    }

    private fun deleteChildren(dir: DocumentFile) {
        dir.listFiles().forEach { child ->
            if (child.isDirectory) deleteChildren(child)
            child.delete()
        }
    }

    private fun collectPaths(dir: DocumentFile, prefix: String, out: MutableList<String>) {
        dir.listFiles().forEach { child ->
            val path = if (prefix.isBlank()) child.name.orEmpty() else "$prefix/${child.name.orEmpty()}"
            if (child.isDirectory) {
                out.add("$path/")
                collectPaths(child, path, out)
            } else {
                out.add(path)
            }
        }
    }
}

