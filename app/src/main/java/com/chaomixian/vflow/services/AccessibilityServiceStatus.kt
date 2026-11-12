package com.chaomixian.vflow.services

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.chaomixian.vflow.ui.viewmodel.SettingsViewModel
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

object AccessibilityServiceStatus {

    fun getOriginalServiceId(context: Context): String {
        return ComponentName(context, AccessibilityService::class.java).flattenToString()
    }

    fun getDisguisedServiceId(context: Context): String {
        return ComponentName(context, SelectToSpeakService::class.java).flattenToString()
    }

    fun getServiceId(context: Context): String {
        val prefs = context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val disguised = prefs.getBoolean(SettingsViewModel.KEY_ACCESSIBILITY_DISGUISE, false)
        return if (disguised) getDisguisedServiceId(context) else getOriginalServiceId(context)
    }

    fun isEnabledInSettings(context: Context): Boolean {
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        // 原服务和伪装服务都认。
        // 原来只认「当前伪装开关对应的那个」，用户在切换开关前后启用过另一个时，
        // 明明开着无障碍也会被判成未授权（对话页里表现为“需要权限：无障碍服务”）。
        return containsServiceId(enabledServicesSetting, getOriginalServiceId(context)) ||
            containsServiceId(enabledServicesSetting, getDisguisedServiceId(context))
    }

    fun isRunning(context: Context): Boolean {
        return ServiceStateBus.isAccessibilityServiceRunning()
    }

    internal fun containsServiceId(enabledServicesSetting: String?, expectedServiceId: String): Boolean {
        if (enabledServicesSetting.isNullOrBlank()) {
            return false
        }

        val expected = ComponentName.unflattenFromString(expectedServiceId)
        return enabledServicesSetting
            .split(':')
            .any { entry ->
                entry.equals(expectedServiceId, ignoreCase = true) ||
                    (expected != null && matchesComponent(entry, expected))
            }
    }

    /**
     * 按「包名 + 类名」比较，兼容 Settings 里可能写入的简写形式。
     * 有些 ROM 把组件名存成 `pkg/.services.AccessibilityService`，
     * 直接和 `flattenToString()` 的完整类名比会判成不相等。
     */
    private fun matchesComponent(entry: String, expected: ComponentName): Boolean {
        val actual = ComponentName.unflattenFromString(entry) ?: return false
        if (!actual.packageName.equals(expected.packageName, ignoreCase = true)) return false
        return actual.className.equals(expected.className, ignoreCase = true)
    }

    internal fun replaceServiceId(
        enabledServicesSetting: String?,
        fromServiceId: String,
        toServiceId: String
    ): String {
        val entries = enabledServicesSetting
            .orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot {
                it.equals(fromServiceId, ignoreCase = true) ||
                    it.equals(toServiceId, ignoreCase = true)
            }
            .toMutableList()

        entries += toServiceId
        return entries.joinToString(":")
    }
}
