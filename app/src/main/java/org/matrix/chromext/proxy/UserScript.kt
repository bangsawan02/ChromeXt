package org.matrix.chromext.proxy

import android.net.Uri
import android.view.ContextThemeWrapper
import java.lang.reflect.Modifier
import org.matrix.chromext.Chrome
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.findField
import org.matrix.chromext.utils.findFieldOrNull
import org.matrix.chromext.utils.findMethod
import org.matrix.chromext.utils.findMethodOrNull
import org.matrix.chromext.utils.invokeMethod
import org.matrix.chromext.utils.parseOrigin

object UserScriptProxy {
  // It is possible to do a HTTP POST with LoadUrlParams Class
  // grep org/chromium/content_public/common/ResourceRequestBody to get setPostData in
  // org/chromium/content_public/browser/LoadUrlParams

  val gURL by lazy { Chrome.loadOrNull("org.chromium.url.GURL") ?: Chrome.load("org.chromium.url.GURL") }
  val loadUrlParams by lazy {
      if (Chrome.isSamsung) {
        Chrome.load("com.sec.android.app.sbrowser.tab.LoadUrlParams")
      } else {
        Chrome.load("org.chromium.content_public.browser.LoadUrlParams")
      }
  }
  val tabWebContentsDelegateAndroidImpl by lazy {
      if (Chrome.isSamsung) {
        Chrome.load("com.sec.android.app.sbrowser.tab.Tab")
      } else {
        Chrome.load("org.chromium.chrome.browser.tab.TabWebContentsDelegateAndroidImpl")
      }
  }
  val webContentsObserverProxy by lazy {
      Chrome.loadOrNull("org.chromium.content.browser.webcontents.WebContentsObserverProxy")
          ?: Chrome.loadOrNull("org.chromium.content_public.browser.WebContentsObserver")
          ?: Chrome.loadOrNull("org.chromium.chrome.browser.tab.TabWebContentsObserver")
  }
  val navigationControllerImpl by lazy {
      Chrome.loadOrNull("org.chromium.content.browser.framehost.NavigationControllerImpl")
          ?: Chrome.load("org.chromium.content.browser.framehost.NavigationControllerImpl")
  }
  val chromeTabbedActivity by lazy {
      if (Chrome.isSamsung) {
        Chrome.load("com.sec.terrace.TerraceActivity")
      } else {
        Chrome.load("org.chromium.chrome.browser.ChromeTabbedActivity")
      }
  }
  val tabImpl by lazy {
      if (Chrome.isSamsung) {
        Chrome.load("com.sec.terrace.Terrace")
      } else {
        Chrome.loadOrNull("org.chromium.chrome.browser.tab.TabImpl")
            ?: Chrome.load("org.chromium.chrome.browser.tab.Tab")
      }
  }
  val getId by lazy { findMethodOrNull(tabImpl, true) { name == "getId" } }
  val mId by lazy {
      val targetClass = if (Chrome.isSamsung) tabWebContentsDelegateAndroidImpl else tabImpl
      findFieldOrNull(targetClass, true) { name == "mId" }
          ?: targetClass.declaredFields
              .run {
                val target = find { it.name == "mId" }
                if (target == null) {
                  val profile = Chrome.loadOrNull("org.chromium.chrome.browser.profiles.Profile")
                  val windowAndroid = Chrome.loadOrNull("org.chromium.ui.base.WindowAndroid")
                  var startIndex = indexOfFirst { it.type == gURL }
                  val endIndex = indexOfFirst {
                    (profile != null && it.type == profile) ||
                        it.type == ContextThemeWrapper::class.java ||
                        (windowAndroid != null && it.type == windowAndroid)
                  }
                  if (startIndex == -1 || startIndex > endIndex) startIndex = 0
                  val found =
                      if (endIndex > startIndex)
                          slice(startIndex until endIndex).findLast {
                            it.type == Int::class.java || it.type == java.lang.Integer.TYPE
                          }
                      else null
                  found
                      ?: find { it.type == Int::class.java || it.type == java.lang.Integer.TYPE }
                      ?: targetClass.declaredFields.first()
                } else target
              }
              .also { it.isAccessible = true }
  }
  val mTab by lazy {
      findFieldOrNull(tabWebContentsDelegateAndroidImpl, true) { type == tabImpl || tabImpl.isAssignableFrom(type) }
          ?: findField(tabWebContentsDelegateAndroidImpl, true) { !type.isPrimitive }
  }
  val mIsLoading by lazy {
      findFieldOrNull(tabImpl, true) { name == "mIsLoading" }
          ?: tabImpl.declaredFields
              .run {
                val target = find { it.name == "mIsLoading" }
                if (target == null) {
                  val webContents = Chrome.loadOrNull("org.chromium.content_public.browser.WebContents")
                  val startIndex =
                      maxOf(
                          if (webContents != null) indexOfFirst { it.type == webContents } else -1,
                          indexOfFirst { it.type == loadUrlParams })
                  val range = if (startIndex >= 0 && startIndex < size) slice(startIndex until size) else this.toList()
                  range.find {
                    (it.type == Boolean::class.java || it.type == java.lang.Boolean.TYPE) && !Modifier.isStatic(it.modifiers)
                  } ?: find { (it.type == Boolean::class.java || it.type == java.lang.Boolean.TYPE) && !Modifier.isStatic(it.modifiers) }
                  ?: tabImpl.declaredFields.first()
                } else target
              }
              .also { it.isAccessible = true }
  }
  val getUrl by lazy {
      findMethodOrNull(tabImpl, true) { returnType == gURL || returnType.name.contains("GURL") }
          ?: findMethodOrNull(tabImpl, true) { name == "getUrl" }
  }
  val loadUrl by lazy {
      findMethodOrNull(if (Chrome.isSamsung) tabWebContentsDelegateAndroidImpl else tabImpl, true) {
        parameterTypes contentDeepEquals arrayOf(loadUrlParams) &&
            (Chrome.isSamsung || returnType != Void.TYPE)
      } ?: findMethod(if (Chrome.isSamsung) tabWebContentsDelegateAndroidImpl else tabImpl, true) {
        parameterTypes.size == 1 && parameterTypes[0] == loadUrlParams
      }
  }

  val kMaxURLChars = 2097152

  private fun loadUrl(url: String, tab: Any? = Chrome.getTab()) {
    if (!Chrome.isSamsung && !Chrome.checkTab(tab)) return
    loadUrl.invoke(tab, newLoadUrlParams(url))
  }

  fun getTabId(tab: Any): String {
    val id = getId?.invoke(tab) ?: mId.get(tab)!!
    return id.toString()
  }

  fun newLoadUrlParams(url: String): Any {
    val constructor =
        loadUrlParams.declaredConstructors.find { it.parameterTypes.contains(String::class.java) }!!
    val types = constructor.parameterTypes
    if (types contentDeepEquals arrayOf(Int::class.java, String::class.java)) {
      return constructor.newInstance(0, url)
    } else if (types contentDeepEquals arrayOf(String::class.java, Int::class.java)) {
      return constructor.newInstance(url, 0)
    } else {
      return constructor.newInstance(url)
    }
  }

  fun evaluateJavascript(script: String, tab: Any? = Chrome.getTab()): Boolean {
    if (script == "") return true
    if (Chrome.isSamsung) {
      mTab.get(tab ?: Chrome.getTab())?.invokeMethod(script, null) {
        name == "evaluateJavaScriptForTests"
      }
      return true
    }
    if (script.length > kMaxURLChars - 20000) return false
    val code = Uri.encode(script)
    if (code.length < kMaxURLChars - 200) {
      loadUrl("javascript:${code}", tab ?: Chrome.getTab())
      return true
    } else {
      return false
    }
  }

  fun getTab(delegate: Any): Any? {
    return if (Chrome.isSamsung) delegate else mTab.get(delegate)
  }

  fun parseUrl(packed: Any?): String? {
    if (packed == null) {
      return null
    } else if (packed::class.java == String::class.java) {
      return packed as String
    } else if (packed::class.java == loadUrlParams) {
      return runCatching { loadUrlParams.getDeclaredField("a").also { it.isAccessible = true }.get(packed) as String }
          .getOrElse {
            loadUrlParams.declaredFields.firstOrNull { it.type == String::class.java }
                ?.also { it.isAccessible = true }
                ?.get(packed) as? String
          }
    } else if (packed::class.java == gURL) {
      return runCatching { gURL.getDeclaredField("a").also { it.isAccessible = true }.get(packed) as String }
          .getOrElse {
            gURL.declaredFields.firstOrNull { it.type == String::class.java }
                ?.also { it.isAccessible = true }
                ?.get(packed) as? String
          }
    }
    Log.e("parseUrl: ${packed::class.java} is not ${loadUrlParams.name} nor ${gURL.name}")
    return null
  }

  fun userAgentHook(url: String, urlParams: Any): Boolean {
    val origin = parseOrigin(url)
    if (origin != null) {
      // Log.d("Change User-Agent header: ${origin}")
      if (ScriptDbManager.userAgents.contains(origin)) {
        val header = "user-agent: ${ScriptDbManager.userAgents.get(origin)}\r\n"
        if (Chrome.isSamsung) {
          urlParams.invokeMethod(header) { name == "setVerbatimHeaders" }
        } else {
          val mVerbatimHeaders =
              loadUrlParams.declaredFields.filter { it.type == String::class.java }[1]
          mVerbatimHeaders.set(urlParams, header)
        }
        return true
      }
    }
    return false
  }
}
