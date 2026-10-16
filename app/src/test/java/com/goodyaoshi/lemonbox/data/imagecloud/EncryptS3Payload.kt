package com.goodyaoshi.lemonbox.data.imagecloud

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * 生成 `LEMON_S3_PAYLOAD_ENCRYPTED` 的命令行工具，由 Gradle 任务 `encryptS3Payload` 运行。
 *
 * **为什么放在 unit test 源码集**：它不是 App 运行时的一部分，不该被打进 APK；放在这里又能
 * 直接复用 App 用来解密的**同一份** [S3PayloadCipher] 与 [ImageCloudCredentials]——
 * 于是「生成工具与 App 的派生参数/字段漂移」这种只会在发版后才暴露的问题，从根上不存在。
 *
 * 用法（口令走环境变量，避免留在 shell 历史与进程列表里）：
 * ```
 * # 1) 写一份配置 JSON（字段见 ImageCloudCredentials，仅本地留存、勿提交）
 * # 2) 生成密文
 * $env:S3_PAYLOAD_PASSPHRASE='你的口令'; .\gradlew encryptS3Payload --args="--in s3cfg.json"
 * # 3) 核对（可选）
 * .\gradlew encryptS3Payload --args="--decrypt <密文>"
 * ```
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 输出流固定用 UTF-8。
 *
 * Windows 上 `System.out` 跟着控制台代码页（GBK）走，而 Gradle 控制台按 UTF-8 解读——
 * 直接用 `println` 会把中文提示打成乱码，用户看到一堆问号而不知道哪一步出了问题。
 */
private val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
private val err = PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8")

fun main(args: Array<String>) {
    val options = runCatching { parseArgs(args) }.getOrElse { error ->
        err.println("参数有误：${error.message}")
        out.println(USAGE)
        return
    }
    if (options.help) {
        out.println(USAGE)
        return
    }

    val passphrase = readPassphrase()
    if (passphrase == null) {
        err.println("缺少口令。请用环境变量提供：")
        err.println("  PowerShell：\$env:S3_PAYLOAD_PASSPHRASE='…'; .\\gradlew encryptS3Payload …")
        err.println("  bash：      S3_PAYLOAD_PASSPHRASE='…' ./gradlew encryptS3Payload …")
        return
    }

    if (options.decrypt != null) {
        val plain = S3PayloadCipher.decrypt(options.decrypt, passphrase)
        if (plain == null) {
            err.println("解不开：口令不对，或这段密文不是 v1 格式。")
        } else {
            out.println(plain)
        }
        return
    }

    val inputFile = options.inputFile
    if (inputFile == null) {
        err.println("要指定配置文件：--in <配置文件>（或 --help 看用法）")
        return
    }
    if (!inputFile.isFile) {
        err.println("找不到配置文件：${inputFile.absolutePath}")
        return
    }

    val text = runCatching { inputFile.readText(Charsets.UTF_8) }.getOrElse { error ->
        err.println("配置文件读不了：${error.message}")
        return
    }
    val credentials = runCatching { json.decodeFromString<ImageCloudCredentials>(text) }
        .getOrElse { error ->
            err.println("配置 JSON 解析失败：${error.message}")
            return
        }
    if (!credentials.isComplete) {
        err.println("配置不完整：endpoint / bucket / accessKey / secretKey 都不能为空。")
        return
    }

    val payload = S3PayloadCipher.encrypt(json.encodeToString(credentials), passphrase)

    // 自检：解回来必须逐字段一致，否则宁可报错也别让人把错的密文填进 Secret——
    // 那种错到发版后才暴露，用户只会看到「口令不对」。
    val roundTrip = S3PayloadCipher.decrypt(payload, passphrase)
        ?.let { runCatching { json.decodeFromString<ImageCloudCredentials>(it) }.getOrNull() }
    if (roundTrip != credentials) {
        err.println("自检失败：解出的配置与输入不一致，未输出密文。")
        return
    }

    out.println()
    out.println("把下面这一整行填进 GitHub 仓库 Secret `LEMON_S3_PAYLOAD_ENCRYPTED`（Gradle 属性同名）：")
    out.println()
    out.println(payload)
    out.println()
    out.println("（口令不进 CI、不进仓库，App 里由用户输入；换口令就用新口令重跑本任务并更新该 Secret。）")
}

private const val USAGE = """
用口令把云存储配置加密成随包分发的密文（GitHub Secret: LEMON_S3_PAYLOAD_ENCRYPTED）。

  encryptS3Payload --in <配置文件>      按配置 JSON 生成密文并打印
  encryptS3Payload --decrypt <密文>     用口令反解一段密文，确认填对了
  encryptS3Payload --help               显示本说明

配置 JSON 的字段：endpoint / region / bucket / prefix / accessKey / secretKey
（region 可省，缺省按 auto 处理；prefix 允许为空。）

口令从环境变量 S3_PAYLOAD_PASSPHRASE 读取；拿不到控制台时不会退化成命令行参数，
以免口令留在 shell 历史与进程列表里。
"""

private class Options(
    val inputFile: File?,
    val decrypt: String?,
    val help: Boolean
)

private fun parseArgs(args: Array<String>): Options {
    var inputFile: File? = null
    var decrypt: String? = null
    var help = false
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--in" -> inputFile = File(requireValue(args, ++index, "--in"))
            "--decrypt" -> decrypt = requireValue(args, ++index, "--decrypt")
            "--help", "-h" -> help = true
            else -> throw IllegalArgumentException("未知参数：$arg")
        }
        index++
    }
    return Options(inputFile, decrypt, help)
}

private fun requireValue(args: Array<String>, index: Int, option: String): String =
    args.getOrNull(index) ?: throw IllegalArgumentException("$option 后面要跟一个值")

private fun readPassphrase(): CharArray? {
    System.getenv("S3_PAYLOAD_PASSPHRASE")
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it.toCharArray() }
    // Gradle 里跑 JavaExec 时通常拿不到控制台，这时宁可报错也不让口令退化成命令行参数。
    val console = System.console() ?: return null
    return console.readPassword("口令：")
}
