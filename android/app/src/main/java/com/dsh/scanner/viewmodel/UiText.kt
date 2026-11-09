package com.dsh.scanner.viewmodel

import android.content.Context
import androidx.annotation.StringRes

/**
 * Text produced by the ViewModel without holding a Context. The View resolves it
 * at the last possible moment, which keeps the ViewModel unit-testable and all
 * copy in `strings.xml`.
 */
sealed interface UiText {

    /** A string resource plus optional format arguments; arguments can be [UiText]. */
    class Res(@StringRes val id: Int, vararg args: Any) : UiText {
        val arguments: List<Any> = args.toList()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Res) return false
            return id == other.id && arguments == other.arguments
        }

        override fun hashCode(): Int = 31 * id + arguments.hashCode()

        override fun toString(): String = "UiText.Res(id=$id, args=$arguments)"
    }

    data class Raw(val value: String) : UiText
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> {
        val resolved = arguments
            .map { if (it is UiText) it.resolve(context) else it }
            .toTypedArray()
        if (resolved.isEmpty()) context.getString(id) else context.getString(id, *resolved)
    }

    is UiText.Raw -> value
}
