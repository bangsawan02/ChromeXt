package org.matrix.chromext.proxy

import org.matrix.chromext.Chrome
import org.matrix.chromext.utils.findField

object PageMenuProxy {

  val chromeTabbedActivity by lazy { UserScriptProxy.chromeTabbedActivity }
  val customTabActivity by lazy { Chrome.load("org.chromium.chrome.browser.customtabs.CustomTabActivity") }
  val propertyModel by lazy { Chrome.load("org.chromium.ui.modelutil.PropertyModel") }
  val tab by lazy { Chrome.load("org.chromium.chrome.browser.tab.Tab") }
  val emptyTabObserver by lazy {
      Chrome.load("org.chromium.chrome.browser.login.ChromeHttpAuthHandler").superclass as Class<*>
  }
  val tabImpl by lazy { UserScriptProxy.tabImpl }
  val mIsLoading by lazy { UserScriptProxy.mIsLoading }
  val mObservers by lazy { findField(tabImpl) { type.interfaces.contains(Iterable::class.java) } }
}
