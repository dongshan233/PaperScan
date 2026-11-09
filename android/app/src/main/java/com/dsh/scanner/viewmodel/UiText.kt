package com.dsh.scanner.viewmodel

import android.content.Context
import androidx.annotation.StringRes

/**
 * ViewModel 产出的文本，不持有 Context；View 在最后一步调用 [resolve]。
 * 这样文案统一放在 `strings.xml`，ViewModel 也能在纯 JVM 测试里比较。
 */
sealed interface UiText {

    /** 一个 `strings.xml` 资源加上可选的格式化参数。 */
    class Res(
        /** 字符串资源 id，例如 `R.string.status_saved`。 */
        @StringRes val id: Int,

        /** 格式化参数，顺序对应资源里的 `%1$s`、`%2$.2f` 等占位符。 */
        vararg args: Any,
    ) : UiText {
        /** 已固定下来的参数列表，参与 equals/hashCode。 */
        val arguments: List<Any> = args.toList()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Res) return false
            return id == other.id && arguments == other.arguments
        }

        override fun hashCode(): Int = 31 * id + arguments.hashCode()

        override fun toString(): String = "UiText.Res(id=$id, args=$arguments)"
    }

    /** 不需要资源解析的原始文本，用于动态内容或空字符串。 */
    data class Raw(
        /** 直接展示的文本。 */
        val value: String,
    ) : UiText
}

/** 把 [UiText] 解析成当前 Context 的字符串；嵌套的 [UiText] 参数会递归解析。 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> {
        val resolved = arguments
            .map { if (it is UiText) it.resolve(context) else it }
            .toTypedArray()
        if (resolved.isEmpty()) context.getString(id) else context.getString(id, *resolved)
    }

    is UiText.Raw -> value
}
