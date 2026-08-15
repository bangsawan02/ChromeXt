package org.matrix.chromext.hook

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.http.HttpResponseCache
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.Chrome
import org.matrix.chromext.Listener
import org.matrix.chromext.proxy.UserScriptProxy
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.findField
import org.matrix.chromext.utils.findFieldOrNull
import org.matrix.chromext.utils.findMethod
import org.matrix.chromext.utils.findMethodOrNull
import org.matrix.chromext.utils.hookAfter
import org.matrix.chromext.utils.hookBefore

object UserScriptHook : BaseHook() {

  override fun init() {

    val proxy = UserScriptProxy

    runCatching {
      proxy.tabImpl.declaredConstructors.forEach {
        it.hookAfter { Chrome.updateTab(it.thisObject) }
      }
    }

    if (Chrome.isSamsung) {
      findMethodOrNull(proxy.tabWebContentsDelegateAndroidImpl) { name == "onDidFinishNavigation" }
          .let {
            if (it == null)
                findMethod(proxy.tabWebContentsDelegateAndroidImpl) {
                  name == "updateBrowserControlsState"
                }
            else it
          }
          .hookAfter { Chrome.updateTab(it.thisObject) }

      runCatching {
            // Avoid exceptions thrown due to signature conficts while binding services
            val ConnectionManager =
                Chrome.load("com.samsung.android.sdk.scs.base.connection.ConnectionManager")
            val mServiceConnection =
                findField(ConnectionManager) { name == "mServiceConnection" }
                    .also { it.isAccessible = true }

            findMethod(ConnectionManager) { name == "connectToService" }
                // (Landroid/content/Context;Landroid/content/Intent;)Z
                .hookBefore {
                  val hook = it
                  val ctx = hook.args[0] as Context
                  val intent = hook.args[1] as Intent
                  val connection = mServiceConnection.get(hook.thisObject) as ServiceConnection
                  runCatching {
                        if (BuildConfig.DEBUG) Log.d("Binding service ${intent} with ${ctx}")
                        val bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                        hook.result = bound
                      }
                      .onFailure {
                        if (BuildConfig.DEBUG) Log.ex(it)
                        hook.result = false
                      }
                }
          }
          .onFailure { if (BuildConfig.DEBUG) Log.ex(it) }

      runCatching {
            // Avoid version codes mismatch when isolated child services are connected
            val childProcessConnection =
                Chrome.load("org.chromium.base.process_launcher.ChildProcessConnection")
            val packageUtils = Chrome.load("org.chromium.base.PackageUtils")
            val buildInfo = Chrome.load("org.chromium.base.BuildInfo")
            val buildConifg = Chrome.load("org.chromium.build.BuildConfig")

            val mServiceName = findField(childProcessConnection) { name == "mServiceName" }
            val getApplicationPackageInfo =
                findMethod(packageUtils) { name == "getApplicationPackageInfo" }
            val packageVersionCode = findMethod(buildInfo) { name == "packageVersionCode" }
            val version_code = findFieldOrNull(buildConifg) { name == "VERSION_CODE" }

            if (version_code != null) {
              findMethod(childProcessConnection) { name == "onServiceConnectedOnLauncherThread" }
                  // (Landroid/os/IBinder;)V
                  .hookBefore {
                    val latestPackage = getApplicationPackageInfo.invoke(null, 0)
                    val latestVersionCode = packageVersionCode.invoke(null, latestPackage)
                    val loadedVersionCode = version_code.get(null)
                    if (latestVersionCode != loadedVersionCode) {
                      Log.d(
                          "Version codes mismatched for child services ${mServiceName.get(it.thisObject)}")
                      version_code.set(null, latestVersionCode)
                    }
                  }
            }
          }
          .onFailure { if (BuildConfig.DEBUG) Log.ex(it) }
    }

    findMethodOrNull(proxy.tabImpl, true) {
          name == "onPageLoadFinished" || name == "didStopLoading"
        }
        ?.hookAfter {
          Chrome.updateTab(it.thisObject)
          val url = Chrome.getUrl(it.thisObject)
          if (url != null && url.isNotEmpty() && !url.startsWith("chrome")) {
            ScriptDbManager.invokeScript(url, it.thisObject)
          }
        }

    findMethodOrNull(proxy.tabWebContentsDelegateAndroidImpl, true) {
          name == "didStopLoading" || name == "onPageLoadFinished"
        }
        ?.hookAfter {
          val tab = runCatching { proxy.mTab.get(it.thisObject) }.getOrNull() ?: Chrome.getTab()
          if (tab != null) {
            Chrome.updateTab(tab)
            val url = Chrome.getUrl(tab) ?: (if (it.args.isNotEmpty()) proxy.parseUrl(it.args[0]) else null)
            if (url != null && url.isNotEmpty() && !url.startsWith("chrome")) {
              ScriptDbManager.invokeScript(url, tab)
            }
          }
        }

    proxy.webContentsObserverProxy?.let { observerClass ->
      findMethodOrNull(observerClass, true) {
            name == "didStopLoading"
          }
          ?.hookAfter {
            val tab = Chrome.getTab()
            var url = if (tab != null && proxy.getUrl != null) proxy.parseUrl(proxy.getUrl?.invoke(tab)) else null
            if (url == null || url.isEmpty()) {
              if (it.args.isNotEmpty()) {
                url = proxy.parseUrl(it.args[0])
              }
            }
            if (url != null && url.isNotEmpty() && !url.startsWith("chrome")) {
              ScriptDbManager.invokeScript(url)
            }
          }
    }

    findMethodOrNull(proxy.tabWebContentsDelegateAndroidImpl, true) {
          name == if (Chrome.isSamsung) "onAddMessageToConsole" else "addMessageToConsole"
        }
        // public boolean addMessageToConsole(int level, String message, int lineNumber,
        // String sourceId)
        ?.hookAfter {
          // This should be the way to communicate with the front-end of ChromeXt
          val lineNumber = it.args[2] as Int
          val sourceId = it.args[3] as String
          if (it.args[0] as Int == 0 &&
              sourceId.startsWith("local://ChromeXt/init") &&
              lineNumber == Local.anchorInChromeXt) {
            Listener.startAction(it.args[1] as String, proxy.getTab(it.thisObject), null, sourceId)
          } else {
            Log.d(
                when (it.args[0] as Int) {
                  0 -> "D"
                  2 -> "W"
                  3 -> "E"
                  else -> "V"
                } + ": [${sourceId}@${lineNumber}] ${it.args[1]}")
          }
        }

    findMethodOrNull(proxy.navigationControllerImpl, true) {
          name == "loadUrl" || parameterTypes contentDeepEquals arrayOf(proxy.loadUrlParams)
        }
        // public void loadUrl(LoadUrlParams params)
        ?.hookBefore {
          val url = proxy.parseUrl(it.args[0])!!
          proxy.userAgentHook(url, it.args[0])
        }

    findMethodOrNull(proxy.chromeTabbedActivity, true) { name == "onResume" }
        ?.hookBefore { Chrome.init(it.thisObject as Context) }

    findMethodOrNull(proxy.chromeTabbedActivity, true) { name == "onStop" }
        ?.hookBefore {
          ScriptDbManager.updateScriptStorage()
          val cache = HttpResponseCache.getInstalled()
          Log.d("HttpResponseCache: Hit ${cache.hitCount} / NetWork ${cache.networkCount}")
          cache.flush()
        }
    isInit = true
  }
}
