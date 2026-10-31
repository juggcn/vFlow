package com.chaomixian.vflow.core.module

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import li.songe.json5.Json5
import li.songe.selector.Selector
import org.junit.Test

/**
 * 网上 GKD 订阅的全量选择器语法校验。
 *
 * 为什么要这个测试：GKDTriggerHandler.parseRule 里，选择器解析失败的规则会被 parseSelectors
 * 静默丢弃，订阅照样「加载成功」，但那些规则永远不会触发，界面上完全看不出来。
 * 所以把整份订阅的每条选择器都跑一遍 Selector.parse，失败就报出来。
 *
 * 资源文件 gkd-subscription.json5 是「梦念逍遥のGKD订阅」的 dist 产物，用于回归验证：
 * 订阅作者更新后，若出现 vFlow 解析不了的新语法，这个测试会先失败。
 */
class SubscriptionSelectorSyntaxTest {

    private val selectorKeys = listOf("matches", "anyMatches", "excludeMatches", "excludeAllMatches")

    private fun JsonObject.selectors(): List<String> = selectorKeys.flatMap { key ->
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    }

    private fun JsonArray?.rules(): List<JsonObject> =
        this?.mapNotNull { it as? JsonObject } ?: emptyList()

    @Test
    fun `every selector in the subscription must parse`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("gkd-subscription.json5")
            ?: throw AssertionError("缺少测试资源 gkd-subscription.json5")
        val root = Json5.parseToJson5Element(stream.bufferedReader().readText()) as? JsonObject
            ?: throw AssertionError("订阅根节点不是对象")

        val selectors = mutableListOf<String>()

        // apps[].groups[].rules[]
        (root["apps"] as? JsonArray)?.forEach { app ->
            val groups = (app as? JsonObject)?.get("groups") as? JsonArray
            groups?.forEach { group ->
                ((group as? JsonObject)?.get("rules") as? JsonArray).rules()
                    .forEach { rule -> selectors.addAll(rule.selectors()) }
            }
        }

        // globalGroups[].rules[]
        (root["globalGroups"] as? JsonArray)?.forEach { group ->
            ((group as? JsonObject)?.get("rules") as? JsonArray).rules()
                .forEach { rule -> selectors.addAll(rule.selectors()) }
        }

        if (selectors.isEmpty()) throw AssertionError("订阅里没解析出任何选择器")

        val failures = mutableListOf<String>()
        selectors.forEach { source ->
            try {
                Selector.parse(source)
            } catch (e: Exception) {
                failures.add("$source  ->  ${e.message}")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "共 ${selectors.size} 条选择器，${failures.size} 条解析失败:\n" +
                        failures.take(10).joinToString("\n")
            )
        }
    }
}
