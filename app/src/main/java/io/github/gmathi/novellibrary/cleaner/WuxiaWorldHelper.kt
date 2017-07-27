package io.github.gmathi.novellibrary.cleaner

import android.net.Uri
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File


class WuxiaWorldHelper : HtmlHelper() {


    override fun downloadCSS(doc: Document, downloadDir: File) {
        super.downloadCSS(doc, downloadDir)
    }

    override fun cleanDoc(doc: Document) {
        removeJS(doc)
        var contentElement = doc.body().getElementsByTag("div").firstOrNull { it.hasAttr("itemprop") && it.attr("itemprop") == "articleBody" }
        if (contentElement != null && contentElement.children().size >= 2) {
            contentElement.child(contentElement.children().size - 1).remove()
            contentElement.child(0).remove()
        }
        do {
            contentElement?.siblingElements()?.remove()
            contentElement = contentElement?.parent()
        } while (contentElement?.tagName() != "body")
    }

    override fun downloadImage(element: Element, dir: File): File? {
        val uri = Uri.parse(element.attr("src"))
        if (uri.toString().contains("uploads/avatars")) return null
        else return super.downloadImage(element, dir)
    }

    override fun removeJS(doc: Document) {
        super.removeJS(doc)
        doc.getElementsByTag("noscript").remove()
    }

//    override fun toggleTheme(isDark: Boolean, doc: Document): Document {
//        if (isDark) {
//            doc.head().append("<style id=\"darkTheme\">" +
//                "body { background-color:#131313; color:rgba(255, 255, 255, 0.8); } </style> ")
//        } else {
//            doc.head().getElementById("darkTheme")?.remove()
//        }
//
//        return doc
//    }

    override fun addTitle(doc: Document) {
    }

}
