package com.sonnyapp.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON-RPC 错误。
 *
 * 三种错误码含义完全不同，客户端必须分开处理：
 *   1  "Not Available Now" —— 方法存在，但**当前状态**不可用 → 隐藏控件
 *   3  "illegal argument"  —— 方法**可用**，但**参数写错了**     → 我方 bug
 *   12 "<方法名>"          —— **方法不存在**                   → 该机型永久不支持
 *   403 (HTTP)             —— 该方法不对外开放
 */
class SonyApiException(
    val code: Int,
    val apiMessage: String,
    val method: String?,
    val rawResponse: String?,
) : Exception("Sony API error " + code + " (" + apiMessage + ") on " + (method ?: "?") + " :: " + rawResponse) {

    /** 当前状态不可用：应隐藏对应控件 */
    val isNotAvailableNow: Boolean get() = code == CODE_NOT_AVAILABLE_NOW

    /** 参数错误：说明我方调用写错了 */
    val isIllegalArgument: Boolean get() = code == CODE_ILLEGAL_ARGUMENT

    /** 方法不存在：该机型完全不支持 */
    val isMethodNotFound: Boolean get() = code == CODE_METHOD_NOT_FOUND

    companion object {
        const val CODE_NOT_AVAILABLE_NOW = 1
        const val CODE_ILLEGAL_ARGUMENT = 3
        const val CODE_METHOD_NOT_FOUND = 12
    }
}

/** getMethodTypes 返回的单个方法签名。 */
class SonyMethodSignature(
    val name: String,
    val params: List<String>,
    val returns: List<String>,
    val version: String,
) {
    override fun toString(): String = name + "(" + params.joinToString(",") + ")"
}

/**
 * JSON-RPC 响应解析。
 *
 * 字段名不统一（实机确认）：
 *   - getAvailableApiList / getApplicationInfo 用 "result"
 *   - **getMethodTypes 用 "results"（复数）**
 *
 * 另外这里会剥掉可能存在的 UTF-8 BOM —— 真实相机不会发，
 *    但导入的抓包文件/工具导出的 JSON 经常会带，不剥会直接抛解码异常。
 */
object SonyJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun normalize(response: String): String =
        if (response.isNotEmpty() && response[0] == '\uFEFF') response.substring(1) else response

    private fun root(response: String) = json.parseToJsonElement(normalize(response)).jsonObject

    fun extractResultArrayOrNull(response: String): JsonArray? =
        root(response)["result"]?.jsonArray

    /** 若响应是 error，抛 [SonyApiException]；否则原样返回。 */
    fun throwIfError(response: String, method: String?) {
        val r = try {
            root(response)
        } catch (e: Exception) {
            throw IllegalStateException("响应不是合法 JSON: " + response.take(200), e)
        }
        val err = r["error"] ?: return
        val a = err.jsonArray
        val code = a[0].jsonPrimitive.content.toInt()
        val msg = a[1].jsonPrimitive.content
        throw SonyApiException(code, msg, method, response.take(400))
    }

    /** getAvailableApiList / getEvent 里的方法名数组 */
    fun parseApiList(response: String): List<String> {
        throwIfError(response, "getAvailableApiList")
        val arr = extractResultArrayOrNull(response) ?: return emptyList()
        if (arr.isEmpty()) return emptyList()
        return try {
            arr[0].jsonArray.map { it.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** getApplicationInfo -> (serverName, serverVersion) */
    fun parseApplicationInfo(response: String): Pair<String, String>? {
        throwIfError(response, "getApplicationInfo")
        val arr = extractResultArrayOrNull(response) ?: return null
        if (arr.size < 2) return null
        return Pair(arr[0].jsonPrimitive.content, arr[1].jsonPrimitive.content)
    }

    /** getMethodTypes（**注意复数 results**） */
    fun parseMethodTypes(response: String): List<SonyMethodSignature> {
        throwIfError(response, "getMethodTypes")
        val r = root(response)
        val arr = r["results"]?.jsonArray ?: r["result"]?.jsonArray ?: return emptyList()
        val out = ArrayList<SonyMethodSignature>(arr.size)
        for (e in arr) {
            val a = try { e.jsonArray } catch (ex: Exception) { continue }
            if (a.isEmpty()) continue
            val name = try { a[0].jsonPrimitive.content } catch (ex: Exception) { continue }
            if (name.isEmpty() || !name[0].isLetter()) continue
            val params = try {
                a.getOrNull(1)?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            } catch (ex: Exception) { emptyList() }
            val rets = try {
                a.getOrNull(2)?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            } catch (ex: Exception) { emptyList() }
            val ver = try {
                a.getOrNull(3)?.jsonPrimitive?.content ?: "1.0"
            } catch (ex: Exception) { "1.0" }
            out.add(SonyMethodSignature(name, params, rets, ver))
        }
        return out
    }
}
