package com.chaomixian.vflow.core.module

import li.songe.selector.Selector
import org.junit.Test

/**
 * 去广告工作流用到的选择器语法校验。
 *
 * selector 解析失败时，ElementTriggerHandler.createTriggerState 会 catch 异常并返回 null，
 * 触发器被静默丢弃，界面上看不出任何报错。所以这里把实际要用的串跑一遍解析，
 * 防止「导入成功但规则根本没加载」这种哑失败。
 */
class AdSkipSelectorSyntaxTest {

    private val adSkipSelectors = listOf(
        // 跳过（排除播放器与引导场景）
        "[text*=\"跳过\" && text!*=\"片头\" && text!*=\"片尾\" && text!*=\"引导\" && text!*=\"教程\" && text!*=\"步骤\" && text!*=\"动画\"]",
        // 关闭广告（排除设置项）
        "[text*=\"关闭广告\" && text!*=\"推荐\" && text!*=\"个性化\" && text!*=\"推送\" && text!*=\"设置\"]",
        "[text*=\"广告关闭\" && text!*=\"推荐\" && text!*=\"个性化\" && text!*=\"推送\"]",
        // contentDescription 分支
        "[desc*=\"跳过\" && desc!*=\"片头\" && desc!*=\"片尾\" && desc!*=\"引导\" && desc!*=\"教程\"]",
        "[desc*=\"关闭广告\" && desc!*=\"推荐\" && desc!*=\"个性化\" && desc!*=\"推送\"]",
        // 倒计时纯数字（谨慎档）
        "[text~=\"^[0-9]{1,2}([.][0-9]+)?[sS秒]$\"]",
        // 叉号档
        "[text=\"关闭\"]",
        "[text=\"×\"]",
        "[text=\"✕\"]",
        // contentDescription 的关闭按钮（|| 分支）
        "[desc=\"关闭\" || desc=\"close\" || desc=\"Close\"]"
    )

    @Test
    fun `ad skip selectors must parse`() {
        adSkipSelectors.forEach { source ->
            try {
                Selector.parse(source)
            } catch (e: Exception) {
                throw AssertionError("选择器解析失败: $source\n${e.message}", e)
            }
        }
    }

    @Test
    fun `exclusion conditions must not break parsing`() {
        // 单独确认 !*= 在 text/desc 上都可用
        Selector.parse("[text*=\"跳过\" && text!*=\"片头\"]")
        Selector.parse("[desc*=\"跳过\" && desc!*=\"片头\"]")
        // 单条件基线
        Selector.parse("[text*=\"跳过\"]")
    }
}
