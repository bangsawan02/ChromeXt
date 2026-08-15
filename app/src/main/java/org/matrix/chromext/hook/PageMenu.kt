package org.matrix.chromext.hook

import android.content.Context
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import de.robv.android.xposed.XC_MethodHook.Unhook
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.LinkedHashSet
import org.matrix.chromext.Chrome
import org.matrix.chromext.Listener
import org.matrix.chromext.R
import org.matrix.chromext.Resource
import org.matrix.chromext.proxy.PageMenuProxy
import org.matrix.chromext.proxy.UserScriptProxy
import org.matrix.chromext.script.Local
import org.matrix.chromext.utils.*

enum class AppMenuItemType(val value: Int) {
  /** Regular Android menu item that contains a title and an icon if icon is specified. */
  STANDARD(0),

  /**
   * Menu item that has two buttons, the first one is a title and the second one is an icon. It is
   * different from the regular menu item because it contains two separate buttons.
   */
  TITLE_BUTTON(1),

  /**
   * Menu item that has multiple buttons (no more than 5). Every one of these buttons is displayed
   * as an icon.
   */
  BUTTON_ROW(2),

  /** Menu item that when contains submenus. */
  MENU_ITEM_WITH_SUBMENU(3),

  /** The header for submenus when submenus are displayed in drilldown. */
  SUBMENU_HEADER(4),

  /** A divider item to distinguish between menu item groupings. */
  DIVIDER(5),

  /**
   * The number of menu item types specified above. If you add a menu item type you MUST increment
   * this.
   */
  NUM_ENTRIES(6)
}

enum class EntryPoint(val value: Int) {
  UNKNOWN(0),

  /** The user opened reader mode through an app message. */
  MESSAGE(1),

  /** The user opened reader mode through the app menu. */
  APP_MENU(2),

  /** The user opened reader mode through the toolbar button. */
  TOOLBAR_BUTTON(3),
}

object readerMode {
  val ID = 31415926

  fun activate() {
    runCatching {
      @Suppress("UNCHECKED_CAST")
      val observers = (PageMenuProxy.mObservers.get(Chrome.getTab()) as? Iterable<Any>)?.toList() ?: return
      val readerModeManager =
          observers.find {
            (findFieldOrNull(it::class.java, true) {
              type == LinkedHashSet::class.java && Modifier.isStatic(modifiers)
            } != null ||
                findFieldOrNull(it::class.java, true) { Set::class.java.isAssignableFrom(type) } != null) &&
                findFieldOrNull(it::class.java, true) {
                  type == PageMenuProxy.propertyModel || PageMenuProxy.propertyModel.isAssignableFrom(type)
                } != null
          } ?: return

      val activateMethod =
          findMethodOrNull(readerModeManager::class.java, true) {
            // public void activateReaderMode(@EntryPoint int entryPoint)
            parameterTypes contentEquals arrayOf(Int::class.java) &&
                !Modifier.isStatic(modifiers) &&
                returnType == Void.TYPE
          } ?: findMethodOrNull(readerModeManager::class.java, true) {
            parameterTypes.size == 1 &&
                (parameterTypes[0] == Int::class.java || parameterTypes[0] == java.lang.Integer.TYPE) &&
                !Modifier.isStatic(modifiers)
          }

      activateMethod?.invoke(readerModeManager, EntryPoint.UNKNOWN.value)
    }
  }
}

object PageMenuHook : BaseHook() {

  private fun getUrl(): String {
    return Chrome.getUrl()!!
  }

  override fun init() {

    if (ContextMenuHook.isInit) return
    val proxy = PageMenuProxy

    fun menuHandler(ctx: Context, id: Int): Boolean {
      if (id == readerMode.ID) {
        readerMode.activate()
        return true
      }
      when (ctx.resources.getResourceName(id)) {
        "org.matrix.chromext:id/extension_id" -> {
          Listener.on("extension")
        }
        "org.matrix.chromext:id/install_script_id" -> {
          val sandBoxed = shouldBypassSandbox(getUrl())
          Chrome.evaluateJavascript(listOf("Symbol.installScript(true);"), null, null, sandBoxed)
        }
        "org.matrix.chromext:id/developer_tools_id" -> Listener.on("inspectPages")
        "org.matrix.chromext:id/eruda_console_id" ->
            UserScriptProxy.evaluateJavascript(Local.openEruda)
        "${ctx.packageName}:id/reload_menu_id" -> {
          val isLoading = proxy.mIsLoading.get(Chrome.getTab()) as Boolean
          if (!isLoading) return Listener.on("userAgentSpoof", getUrl()) != null
        }
      }
      return false
    }

    findMethodOrNull(proxy.chromeTabbedActivity, true) {
          // public boolean onMenuOrKeyboardAction(int id, boolean fromMenu, ? triggeringMotion)
          (parameterCount == 2 || parameterCount == 3) &&
              parameterTypes[0] == Int::class.java &&
              parameterTypes[1] == Boolean::class.java &&
              returnType == Boolean::class.java
        }
        ?.hookBefore {
          if (menuHandler(it.thisObject as Context, it.args[0] as Int)) {
            it.result = true
          }
        }

    findMethodOrNull(proxy.customTabActivity, true) {
          // public boolean onMenuOrKeyboardAction(int id, boolean fromMenu, ? triggeringMotion)
          (parameterCount == 2 || parameterCount == 3) &&
              parameterTypes[0] == Int::class.java &&
              parameterTypes[1] == Boolean::class.java &&
              returnType == Boolean::class.java
        }
        ?.hookBefore {
          if (menuHandler(it.thisObject as Context, it.args[0] as Int)) {
            it.result = true
          }
        }

    findMethodOrNull(proxy.chromeTabbedActivity, true) {
          parameterTypes.size == 0 &&
              returnType.declaredMethods.size >= 6 &&
              (returnType.declaredMethods.find {
                // Bundle getBundleForMenuItem(int itemId);
                it.returnType == Bundle::class.java && it.parameterTypes.size == 1
              } != null) &&
              (returnType.declaredFields.size == 0 ||
                  returnType.declaredFields.find {
                    Context::class.java.isAssignableFrom(it.type)
                  } != null) &&
              (returnType.isInterface() || Modifier.isAbstract(returnType.modifiers))
        }
        ?.hookAfter {
          val tabbedAppMenuPropertiesDelegate = it.result::class.java
          inflateAppMenu(tabbedAppMenuPropertiesDelegate)
        }

    isInit = true
  }

  fun inflateAppMenu(tabbedAppMenuPropertiesDelegate: Class<*>): Unhook? {
    val proxy = PageMenuProxy
    val appMenuPropertiesDelegateImpl =
        if (tabbedAppMenuPropertiesDelegate.superclass != null &&
            tabbedAppMenuPropertiesDelegate.superclass != Any::class.java) {
          tabbedAppMenuPropertiesDelegate.superclass as Class<*>
        } else {
          tabbedAppMenuPropertiesDelegate
        }
    // Can be found by searching `Android.PrepareMenu`

    val mContext =
        findFieldOrNull(tabbedAppMenuPropertiesDelegate, true) {
          Context::class.java.isAssignableFrom(type)
        } ?: findFieldOrNull(appMenuPropertiesDelegateImpl, true) {
          Context::class.java.isAssignableFrom(type)
        } ?: run {
          val ctor = appMenuPropertiesDelegateImpl.declaredConstructors.firstOrNull()
          if (ctor != null && ctor.parameterTypes.isNotEmpty()) {
            findFieldOrNull(appMenuPropertiesDelegateImpl, true) { type == ctor.parameterTypes[0] }
          } else null
        }

    val mActivityTabProvider =
        findFieldOrNull(tabbedAppMenuPropertiesDelegate, true) {
          type.name.contains("ActivityTabProvider") ||
              type.name.contains("TabProvider") ||
              type.name.contains("Supplier") ||
              type.name.contains("TabModel") ||
              (type.declaredMethods.any { it.name == "get" && it.parameterTypes.isEmpty() })
        } ?: findFieldOrNull(appMenuPropertiesDelegateImpl, true) {
          type.name.contains("ActivityTabProvider") ||
              type.name.contains("TabProvider") ||
              type.name.contains("Supplier") ||
              type.name.contains("TabModel") ||
              (type.declaredMethods.any { it.name == "get" && it.parameterTypes.isEmpty() })
        } ?: run {
          val ctor = appMenuPropertiesDelegateImpl.declaredConstructors.firstOrNull()
          if (ctor != null && ctor.parameterTypes.size > 1) {
            findFieldOrNull(appMenuPropertiesDelegateImpl, true) { type == ctor.parameterTypes[1] }
          } else null
        }

    if (Chrome.isBrave && mContext != null) {
      // Brave browser replaces the first row menu with class AppMenuIconRowFooter,
      // and it customize the menu by onFooterViewInflated() function in
      // https://github.com/brave/brave-core/blob/master/android/java/
      // org/chromium/chrome/browser/appmenu/BraveTabbedAppMenuPropertiesDelegate.java
      findMethodOrNull(tabbedAppMenuPropertiesDelegate, true) {
            parameterTypes.size == 2 && getParameterTypes()[1] == View::class.java
          }
          ?.hookAfter {
            runCatching {
              val appMenuIconRowFooter = it.args[1] as LinearLayout
              val bookmarkButton =
                  (appMenuIconRowFooter.getChildAt(1) as LinearLayout).getChildAt(1) as ImageButton
              bookmarkButton.setVisibility(View.VISIBLE)
              val ctx = mContext.get(it.thisObject) as Context
              Resource.enrich(ctx)
              bookmarkButton.setImageResource(R.drawable.ic_book)
              bookmarkButton.setId(readerMode.ID)
            }
          }
    }

    val prepareMenu =
        findMethodOrNull(appMenuPropertiesDelegateImpl, true) {
          parameterTypes.size == 2 &&
              parameterTypes.first() == Menu::class.java &&
              returnType == Void.TYPE &&
              !Modifier.isStatic(modifiers) &&
              !Modifier.isAbstract(modifiers)
        }
    // public void prepareMenu(Menu menu, AppMenuHandler handler)

    if (prepareMenu != null)
        prepareMenu.hookAfter prepare@{
          val tabProvider = mActivityTabProvider?.get(it.thisObject)
          if (tabProvider != null) {
            runCatching { Chrome.updateTab(tabProvider.invokeMethod { name == "get" }) }
          }
          val ctx = (mContext?.get(it.thisObject) as? Context) ?: Chrome.getContext() ?: return@prepare
          Resource.enrich(ctx)

          val menu = it.args[0] as Menu
          val url = getUrl()

          runCatching {
            val iconRowMenu = menu.getItem(0)
            if (iconRowMenu.hasSubMenu() && !Chrome.isBrave) {
              val infoMenu = iconRowMenu.getSubMenu()?.getItem(3)
              if (infoMenu != null) {
                infoMenu.setIcon(R.drawable.ic_book)
                infoMenu.setEnabled(true)
                val mId = findFieldOrNull(infoMenu::class.java, true) { name == "mId" || type == Int::class.java }
                mId?.set(infoMenu, readerMode.ID)
              }
            }
          }

          val mItems =
              findFieldOrNull(menu::class.java, true) {
                name == "mItems" || List::class.java.isAssignableFrom(type) || ArrayList::class.java.isAssignableFrom(type)
              } ?: return@prepare

          @Suppress("UNCHECKED_CAST")
          val items = (mItems.get(menu) as? ArrayList<MenuItem>) ?: return@prepare

          val skip = items.filter { it.isVisible() }.size <= 10 || isChromeScheme(url)
          // Inflate only for the main_menu, which has more than visible 10 items at least

          if (skip && !isUserScript(url)) return@prepare
          MenuInflater(ctx).inflate(R.menu.main_menu, menu)

          // Show items with indices in main_menu.xml
          val toShow = mutableListOf<Int>(1) // Reversed index in main_menu

          if (isDevToolsFrontEnd(url)) {
            toShow.clear()
          }

          if (isUserScript(url)) {
            toShow.clear()
            toShow.add(2)
            if (skip) {
              // Show this menu for local preview pages (Custom Tab) of UserScripts
              items.find { it.itemId == R.id.install_script_id }?.setVisible(true)
              return@prepare
            }
          }

          if (isChromeXtFrontEnd(url)) {
            toShow.clear()
            toShow.addAll(listOf(3, 4))
          }

          if (!Chrome.isVivaldi &&
              ctx.resources.configuration.smallestScreenWidthDp >= DisplayMetrics.DENSITY_XXHIGH &&
              toShow.size == 1 &&
              toShow.first() == 1) {
            runCatching { menu.getItem(0).setVisible(true) }
          }

          val dividerIndices =
              items
                  .withIndex()
                  .filter {
                    runCatching {
                      ctx.resources
                          .getResourceName(it.value.getItemId())
                          .endsWith("id/divider_line_id")
                    }.getOrDefault(false)
                  }
                  .map { it.index }

          val position = if (dividerIndices.size > 1) dividerIndices[1] else items.size - 1

          toShow.forEach {
            if (items.size >= it) {
              val newMenuItem: MenuItem = items[items.size - it]
              newMenuItem.setVisible(true)
              items.add(minOf(items.size, position + 1), newMenuItem)
            }
          }
          repeat(minOf(4, items.size)) { items.removeLast() }
        }

    // Inflate for MVC UI model
    val maybeAddDividerLine =
        findMethodOrNull(tabbedAppMenuPropertiesDelegate, true) {
          parameterTypes.size == 2 &&
              (parameterTypes[1] == Int::class.java || parameterTypes[1] == java.lang.Integer.TYPE) &&
              returnType == Void.TYPE &&
              !Modifier.isAbstract(modifiers)
        }
    // private void maybeAddDividerLine(MVCListAdapter.ModelList modelList, @IdRes int id)

    val buildModelForStandardMenuItem =
        findMethodOrNull(appMenuPropertiesDelegateImpl, true) {
          returnType == proxy.propertyModel &&
              parameterTypes.count { it == Int::class.java || it == java.lang.Integer.TYPE } >= 3
        } ?: findMethodOrNull(tabbedAppMenuPropertiesDelegate, true) {
          returnType == proxy.propertyModel &&
              parameterTypes.count { it == Int::class.java || it == java.lang.Integer.TYPE } >= 3
        } ?: findMethodOrNull(appMenuPropertiesDelegateImpl, true) {
          returnType == proxy.propertyModel
        } ?: findMethodOrNull(tabbedAppMenuPropertiesDelegate, true) {
          returnType == proxy.propertyModel
        }
    // public PropertyModel buildModelForStandardMenuItem(
    // @IdRes int id, @StringRes int titleId, @DrawableRes int iconResId)

    val buildMenuModelListMethod =
        findMethodOrNull(tabbedAppMenuPropertiesDelegate, true) {
          parameterTypes.isEmpty() &&
              returnType.name.contains("ModelList") &&
              !Modifier.isAbstract(modifiers)
        } ?: findMethodOrNull(tabbedAppMenuPropertiesDelegate, true) {
          parameterTypes.isEmpty() &&
              returnType != Void.TYPE &&
              returnType != Boolean::class.java &&
              returnType != java.lang.Boolean.TYPE &&
              returnType != Int::class.java &&
              returnType != java.lang.Integer.TYPE &&
              !returnType.isPrimitive &&
              !Modifier.isAbstract(modifiers)
        }

    val MVCListAdapter_ModelList =
        maybeAddDividerLine?.parameterTypes?.firstOrNull()
            ?: buildMenuModelListMethod?.returnType
            ?: Chrome.loadOrNull("org.chromium.ui.modelutil.MVCListAdapter\$ModelList")
            ?: run {
              Log.d("MVCListAdapter_ModelList not found, falling back to ContextMenuHook")
              if (!ContextMenuHook.isInit) {
                runCatching { ContextMenuHook.init() }
              }
              throw NoSuchMethodException("MVCListAdapter_ModelList not found")
            }

    val mItems =
        findFieldOrNull(MVCListAdapter_ModelList, true) {
          type == ArrayList::class.java ||
              List::class.java.isAssignableFrom(type) ||
              Collection::class.java.isAssignableFrom(type)
        } ?: MVCListAdapter_ModelList.declaredFields.firstOrNull {
          List::class.java.isAssignableFrom(it.type) || Collection::class.java.isAssignableFrom(it.type)
        }?.also { it.isAccessible = true }
        ?: throw NoSuchFieldException("mItems in MVCListAdapter_ModelList not found")

    val MVCListAdapter_ListItem =
        Chrome.loadOrNull("org.chromium.ui.modelutil.MVCListAdapter\$ListItem")
            ?: MVCListAdapter_ModelList.declaringClass?.declaredClasses?.find {
              it.simpleName == "ListItem" || it.name.endsWith("\$ListItem")
            }
            ?: run {
              val excludedReturnValuesForModelItem =
                  arrayOf(
                      MVCListAdapter_ModelList,
                      Int::class.java,
                      java.lang.Integer.TYPE,
                      Boolean::class.java,
                      java.lang.Boolean.TYPE,
                      Void.TYPE,
                      View::class.java)
              val buildNewIncognitoTabItem =
                  findMethodOrNull(tabbedAppMenuPropertiesDelegate, true) {
                    parameterTypes.isEmpty() &&
                        !Modifier.isStatic(modifiers) &&
                        !excludedReturnValuesForModelItem.contains(returnType)
                  }
              buildNewIncognitoTabItem?.returnType
                  ?: throw NoSuchFieldException("MVCListAdapter_ListItem not found")
            }

    val model =
        findFieldOrNull(MVCListAdapter_ListItem, true) {
          type == proxy.propertyModel ||
              proxy.propertyModel.isAssignableFrom(type) ||
              type.name.contains("PropertyModel")
        } ?: MVCListAdapter_ListItem.declaredFields.firstOrNull {
          !it.type.isPrimitive && it.type != java.lang.Integer.TYPE && it.type != Int::class.java
        }?.also { it.isAccessible = true }
        ?: throw NoSuchFieldException("model in MVCListAdapter_ListItem not found")

    val mType =
        findFieldOrNull(MVCListAdapter_ListItem, true) {
          type == Int::class.java || type == java.lang.Integer.TYPE
        } ?: MVCListAdapter_ListItem.declaredFields.firstOrNull {
          it.type == Int::class.java || it.type == java.lang.Integer.TYPE
        }?.also { it.isAccessible = true }
        ?: throw NoSuchFieldException("mType in MVCListAdapter_ListItem not found")

    val mData =
        findFieldOrNull(proxy.propertyModel, true) {
          Map::class.java.isAssignableFrom(type) || type.name.contains("Map")
        } ?: proxy.propertyModel.declaredFields.firstOrNull {
          !Modifier.isStatic(it.modifiers) && (Map::class.java.isAssignableFrom(it.type) || it.type.name.contains("Map"))
        }?.also { it.isAccessible = true }

    val hookTargetMethod =
        buildMenuModelListMethod
            ?: findMethod(tabbedAppMenuPropertiesDelegate, true) {
              parameterTypes.isEmpty() && returnType == MVCListAdapter_ModelList
            }

    return hookTargetMethod.hookAfter {
      val tabProvider = mActivityTabProvider?.get(it.thisObject)
      if (tabProvider != null) {
        runCatching { Chrome.updateTab(tabProvider.invokeMethod { name == "get" }) }
      }
      val ctx = (mContext?.get(it.thisObject) as? Context) ?: Chrome.getContext() ?: return@hookAfter

      Resource.enrich(ctx)
      val url = getUrl()

      @Suppress("UNCHECKED_CAST")
      val menuModels = (mItems.get(it.result) as? MutableList<Any>) ?: return@hookAfter

      runCatching {
        @Suppress("UNCHECKED_CAST")
        val firstModel = if (menuModels.isNotEmpty()) model.get(menuModels[0]) else null
        val iconModels = mData?.let { field ->
          runCatching { field.get(firstModel) as? Map<Any, Any?> }.getOrNull()
        }
        val additionalIcons =
            iconModels?.entries
                ?.find { it.key.toString() == "ADDITIONAL_ICONS" }
                ?.let {
                  val _value = it.value?.let { v -> v::class.java.declaredFields.firstOrNull()?.also { f -> f.isAccessible = true } }
                  _value?.get(it.value)
                }
        if (additionalIcons != null && !Chrome.isBrave) {
          @Suppress("UNCHECKED_CAST")
          val icons = mItems.get(additionalIcons) as? List<Any>
          val targetIcon = icons?.getOrNull(3)
          if (targetIcon != null) {
            val pageInfoModel = mData?.let { field ->
              runCatching { field.get(model.get(targetIcon)) as? Map<Any, Any?> }.getOrNull()
            }
            pageInfoModel?.forEach { (key, value) ->
              if (value != null) {
                val _value = value::class.java.declaredFields.firstOrNull()?.also { f -> f.isAccessible = true }
                if (key.toString() == "MENU_ITEM_ID") {
                  _value?.set(value, readerMode.ID)
                } else if (key.toString() == "ICON") {
                  _value?.set(value, ctx.resources.getDrawable(R.drawable.ic_book, null))
                }
              }
            }
          }
        }
      }

      val skip = menuModels.size <= 10 || isChromeScheme(url)
      if (skip && !isUserScript(url)) return@hookAfter

      fun createMenuItemModel(id: Int, titleRes: Int, iconRes: Int): Any? {
        if (buildModelForStandardMenuItem != null) {
          return runCatching {
            when (buildModelForStandardMenuItem.parameterCount) {
              3 -> buildModelForStandardMenuItem.invoke(it.thisObject, id, titleRes, iconRes)
              4 -> buildModelForStandardMenuItem.invoke(it.thisObject, id, titleRes, iconRes, true)
              5 -> buildModelForStandardMenuItem.invoke(it.thisObject, id, titleRes, iconRes, true, false)
              else -> buildModelForStandardMenuItem.invoke(it.thisObject, id, titleRes, iconRes)
            }
          }.getOrNull()
        }
        return null
      }

      val devToolsModel = createMenuItemModel(R.id.developer_tools_id, R.string.main_menu_developer_tools, R.drawable.ic_devtools)
      val extensionModel = createMenuItemModel(R.id.extension_id, R.string.main_menu_extension, R.drawable.ic_extension)
      val installScriptModel = createMenuItemModel(R.id.install_script_id, R.string.main_menu_install_script, R.drawable.ic_install_script)
      val erudaConsoleModel = createMenuItemModel(R.id.eruda_console_id, R.string.main_menu_eruda_console, R.drawable.ic_devtools)

      val itemConstructor =
          MVCListAdapter_ListItem.declaredConstructors.firstOrNull()?.also { it.isAccessible = true }
              ?: return@hookAfter

      val menusToAdd = mutableListOf<Any>()

      if (isChromeXtFrontEnd(url)) {
        devToolsModel?.let { menusToAdd.add(itemConstructor.newInstance(AppMenuItemType.STANDARD.value, it)) }
        extensionModel?.let { menusToAdd.add(itemConstructor.newInstance(AppMenuItemType.STANDARD.value, it)) }
      } else if (isUserScript(url)) {
        installScriptModel?.let { menusToAdd.add(itemConstructor.newInstance(AppMenuItemType.STANDARD.value, it)) }
      } else {
        erudaConsoleModel?.let { menusToAdd.add(itemConstructor.newInstance(AppMenuItemType.STANDARD.value, it)) }
      }

      if (menusToAdd.isNotEmpty()) {
        val dividers = menuModels.filter {
          runCatching { mType.get(it) == AppMenuItemType.DIVIDER.value }.getOrDefault(false)
        }
        val injectPosition = when {
          dividers.size > 2 -> menuModels.indexOf(dividers[2])
          dividers.isNotEmpty() -> menuModels.indexOf(dividers.last())
          else -> maxOf(0, menuModels.size - 1)
        }
        menuModels.addAll(minOf(menuModels.size, maxOf(0, injectPosition + 1)), menusToAdd)
      }
    }
  }
}
