package com.wmail.core

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/** 邮件 HTML 是不可信输入:先净化再渲染。 */
object MailSanitizer {

    fun clean(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val safelist = Safelist.relaxed()
            .addAttributes(":all", "style", "class", "align", "width", "height")
            .addProtocols("img", "src", "data")
        return Jsoup.clean(html, "https://", safelist)
    }
}
