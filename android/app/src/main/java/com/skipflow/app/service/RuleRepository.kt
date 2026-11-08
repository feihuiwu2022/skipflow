package com.skipflow.app.service

import android.content.Context
import com.skipflow.app.engine.RuleSet
import java.io.File

/**
 * 规则来源，优先级从高到低：
 * 1. /sdcard/Android/data/com.skipflow.app/files/rules.json（adb push 进来热更新，不用重装）
 * 2. APK 内置 assets/default.json（来自仓库根目录 rules/default.json）
 */
object RuleRepository {
    const val OVERRIDE_FILE = "rules.json"
    private const val BUILTIN_ASSET = "default.json"

    class Loaded(val rules: RuleSet, val source: String)

    fun overrideFile(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, OVERRIDE_FILE) }

    fun load(context: Context): Loaded {
        val override = overrideFile(context)
        if (override != null && override.isFile) {
            try {
                return Loaded(RuleSet.parse(override.readText()), override.absolutePath)
            } catch (e: Exception) {
                AppLog.w("外部规则解析失败，改用内置规则: ${e.message}")
            }
        }
        val json = context.assets.open(BUILTIN_ASSET).bufferedReader().use { it.readText() }
        return Loaded(RuleSet.parse(json), "内置 $BUILTIN_ASSET")
    }
}
