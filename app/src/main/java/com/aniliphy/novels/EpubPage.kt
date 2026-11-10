package com.aniliphy.novels

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class PageStyle(
    val paged: Boolean,
    val fontSize: Int,
    val font: NovelFont,
    val lineSpacing: Float,
    val marginDp: Int,
    val topDp: Int,
    val bottomDp: Int,
    val justified: Boolean,
    val background: String,
    val ink: String,
    val illustrations: Boolean,
) {
    fun css(): String = buildString {
        val height = topDp + bottomDp
        append("html{background:$background !important;margin:0 !important;padding:0 !important;}")
        append("body{background:$background !important;color:$ink !important;margin:0 !important;")
        append("font-size:${fontSize}px !important;box-sizing:border-box !important;")
        append("padding:${topDp}px ${marginDp}px ${bottomDp}px ${marginDp}px !important;")
        if (paged) {
            append("height:100vh !important;width:100vw !important;")
            append("column-width:calc(100vw - ${marginDp * 2}px) !important;column-gap:${marginDp * 2}px !important;")
            append("column-fill:auto !important;}")
            append("html{height:100vh !important;width:100vw !important;overflow:hidden !important;}")
        } else {
            append("max-width:100vw !important;overflow-x:hidden !important;}")
        }
        append("body *{color:inherit !important;background-color:transparent !important;")
        append("line-height:$lineSpacing !important;max-width:100% !important;}")
        when (font) {
            NovelFont.Serif -> append("body,body *{font-family:serif !important;}")
            NovelFont.SansSerif -> append("body,body *{font-family:sans-serif !important;}")
            NovelFont.Book -> Unit
        }
        if (justified) {
            append("p,li,blockquote{text-align:justify !important;hyphens:auto !important;}")
        } else {
            append("p,li,blockquote{text-align:left !important;}")
        }
        if (illustrations) {
            append("img,svg{max-width:100% !important;height:auto;max-height:calc(100vh - ${height}px) !important;")
            append("object-fit:contain;break-inside:avoid;}")
        } else {
            append("img,svg,image{display:none !important;}")
        }
        append("a{text-decoration:none !important;}")
        append("a[href]{text-decoration:underline !important;}")
    }
}

object EpubPage {
    const val HOST = "book.local"
    const val ORIGIN = "https://$HOST"

    private val CSP = "default-src 'none'; img-src $ORIGIN data:; style-src $ORIGIN 'unsafe-inline'; font-src $ORIGIN data:"
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr", "image",
    )
    private val OUTSIDE_LINK = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val SELF_CLOSED = Regex("<([a-zA-Z][\\w:-]*)((?:\\s[^<>]*?)?)\\s*/>")
    private val SVG_IMAGE = Regex(
        "<svg\\b[^>]*>\\s*<image\\b[^>]*?(?:xlink:)?href=[\"']([^\"']+)[\"'][^>]*?(?:/>|>\\s*</image>)\\s*</svg>",
        RegexOption.IGNORE_CASE,
    )

    fun prepare(html: String, style: PageStyle, language: String = "en"): String {
        val opened = SVG_IMAGE.replace(html) { match -> "<img src=\"${match.groupValues[1]}\"/>" }
            .let { text ->
                SELF_CLOSED.replace(text) { match ->
                    val tag = match.groupValues[1]
                    if (tag.lowercase() in VOID) match.value else "<$tag${match.groupValues[2]}></$tag>"
                }
            }
        val document: Document = Jsoup.parse(opened)
        document.select("script, iframe, object, embed, form, audio, video, meta[http-equiv], base").remove()
        document.allElements.forEach { element ->
            element.attributes().asList().map { it.key }
                .filter { it.startsWith("on", ignoreCase = true) }
                .forEach(element::removeAttr)
        }
        document.select("a[href]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isEmpty() || href.startsWith("//") || OUTSIDE_LINK.containsMatchIn(href)) link.removeAttr("href")
        }
        val head = document.head()
        head.prependElement("meta").attr("name", "viewport")
            .attr("content", "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no")
        head.prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", CSP)
        head.prependElement("meta").attr("charset", "utf-8")
        head.appendElement("style").attr("id", "aniliphy-reader").appendChild(org.jsoup.nodes.DataNode(style.css()))
        if (document.selectFirst("html")?.hasAttr("lang") != true) document.selectFirst("html")?.attr("lang", language)
        document.outputSettings().syntax(Document.OutputSettings.Syntax.html).prettyPrint(false)
        return "<!doctype html>" + document.selectFirst("html")!!.outerHtml()
    }

    fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "html", "htm", "xhtml", "xml" -> "text/html"
        else -> "application/octet-stream"
    }

    const val SCRIPT = """
window.R=(function(){
  var paged=false,page=0;
  var W=function(){return window.innerWidth;};
  var root=document.documentElement;
  function pages(){return Math.max(1,Math.round(root.scrollWidth/W()));}
  function room(){return Math.max(0,root.scrollHeight-window.innerHeight);}
  function state(edge){
    if(paged){var c=pages();return JSON.stringify({page:page,count:c,pos:c>1?page/(c-1):0,edge:edge||null});}
    var r=room();
    return JSON.stringify({page:0,count:1,pos:r>0?window.scrollY/r:0,edge:edge||null});
  }
  function jump(p){page=p;window.scrollTo({left:p*W(),top:0,behavior:'instant'});}
  return {
    init:function(isPaged,pos){
      paged=isPaged;
      if(paged){jump(Math.round(pos*(pages()-1)));}
      else{window.scrollTo({top:pos*room(),left:0,behavior:'instant'});}
      return state();
    },
    move:function(d){
      if(paged){
        var c=pages(),n=page+d;
        if(n<0)return state('start');
        if(n>=c)return state('end');
        page=n;
        window.scrollTo({left:n*W(),top:0,behavior:'smooth'});
        return state();
      }
      var r=room(),y=window.scrollY;
      if(d>0&&y>=r-2)return state('end');
      if(d<0&&y<=2)return state('start');
      window.scrollBy({top:d*window.innerHeight*0.9,behavior:'smooth'});
      return state();
    },
    state:function(){return state();},
    linkAt:function(x,y){
      var e=document.elementFromPoint(x,y);
      var a=e&&e.closest?e.closest('a[href]'):null;
      return a?a.getAttribute('href'):'';
    },
    anchor:function(id){
      var e=document.getElementById(id)||document.getElementsByName(id)[0];
      if(!e)return state();
      if(paged){
        var p=Math.floor((e.getBoundingClientRect().left+window.scrollX)/W());
        jump(Math.max(0,Math.min(p,pages()-1)));
      }else{e.scrollIntoView({behavior:'instant'});}
      return state();
    },
    atEnd:function(d){
      if(paged)return false;
      var r=room(),y=window.scrollY;
      return d>0?y>=r-2:y<=2;
    }
  };
})();
"""
}
