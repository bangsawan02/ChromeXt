package org.matrix.chromext.proxy

import android.view.View.OnClickListener
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import org.matrix.chromext.Chrome
import org.matrix.chromext.utils.findField

object PageInfoProxy {

  val pageInfoRowView by lazy { Chrome.load("org.chromium.components.page_info.PageInfoRowView") }
  val mIcon by lazy { pageInfoRowView.declaredFields.find { it.type.name.contains("ChromeImageView") }!! }
  val mTitle by lazy { pageInfoRowView.declaredFields.find { it.type == TextView::class.java }!! }
  val mSubtitle by lazy {
      pageInfoRowView.declaredFields.find { it != mTitle && it.type == TextView::class.java }!!
  }

  val pageInfoController by lazy { Chrome.load("org.chromium.components.page_info.PageInfoController") }
  val mView by lazy {
      findField(pageInfoController) {
        (Chrome.isEdge && type == FrameLayout::class.java) ||
            (type.superclass == FrameLayout::class.java &&
                type.interfaces.contains(OnClickListener::class.java))
      }
  }

  private val pageInfoView by lazy {
      if (Chrome.isEdge) Chrome.load("org.chromium.components.page_info.PageInfoView")
      else mView.type
  }
  val mRowWrapper by lazy { findField(pageInfoView) { type == LinearLayout::class.java } }

  val pageInfoControllerRef by lazy {
      // A particular WebContentsObserver designed for PageInfoController
      findField(pageInfoController) {
            type.declaredFields.size == 1 &&
                (type.declaredFields[0].type == pageInfoController ||
                    type.declaredFields[0].type == WeakReference::class.java)
          }
          .type
  }
}
