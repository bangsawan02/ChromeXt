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
  override fun init() {
    // Disabled in favor of standalone manager app UI
    isInit = true
  }
}
