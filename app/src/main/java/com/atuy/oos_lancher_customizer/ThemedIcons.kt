package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class ThemedIcons : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "OOS16_ThemedIcons"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val TARGET_CLASS = "com.android.launcher3.BubbleTextView"
        private const val CLASS_UX_ICON_MANAGER = "com.oplus.icon.OplusUxIconManager"
        private const val ITEM_TYPE_APPLICATION = 0
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        runCatching {
            val bubbleTextViewClass = XposedHelpers.findClass(TARGET_CLASS, lpparam.classLoader)
            val uxIconManagerClass = runCatching {
                XposedHelpers.findClass(CLASS_UX_ICON_MANAGER, lpparam.classLoader)
            }.getOrNull()

            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val context = view.context

                    val itemInfo = param.args.firstOrNull {
                        it?.javaClass?.name?.contains("ItemInfo") == true
                    } ?: return

                    try {
                        val itemType = XposedHelpers.getIntField(itemInfo, "itemType")
                        if (itemType != ITEM_TYPE_APPLICATION) return
                    } catch (ignored: Throwable) {
                    }

                    val componentName = getComponentName(itemInfo) ?: return
                    val userHandle = getUserHandle(itemInfo) ?: android.os.Process.myUserHandle()

                    val originalIcon =
                        loadOriginalIcon(context, componentName, userHandle) ?: return

                    val rawThemedBitmap = generateRawThemedBitmap(context, originalIcon) ?: return
                    val rawThemedDrawable = BitmapDrawable(context.resources, rawThemedBitmap)

                    val finalIcon = if (uxIconManagerClass != null) {
                        processThroughUxIconManager(
                            context,
                            uxIconManagerClass,
                            componentName.packageName,
                            rawThemedDrawable
                        ) ?: rawThemedDrawable
                    } else {
                        rawThemedDrawable
                    }

                    try {
                        XposedHelpers.callMethod(view, "setIcon", finalIcon)
                    } catch (e: Throwable) {
                        (view as? TextView)?.setCompoundDrawablesWithIntrinsicBounds(
                            null,
                            finalIcon,
                            null,
                            null
                        )
                    }
                }
            }

            XposedBridge.hookAllMethods(bubbleTextViewClass, "applyFromWorkspaceItem", hook)
            XposedBridge.hookAllMethods(bubbleTextViewClass, "reapplyItemInfo", hook)

        }.onFailure {
            XposedBridge.log("$TAG: Critical Error -> ${it.message}")
        }
    }


    private fun processThroughUxIconManager(
        context: Context,
        managerClass: Class<*>,
        packageName: String,
        icon: Drawable
    ): Drawable? {
        return try {
            XposedHelpers.callStaticMethod(
                managerClass,
                "getUxIconDrawable",
                context.packageManager,
                packageName,
                icon,
                false
            ) as? Drawable
        } catch (e: Throwable) {
            null
        }
    }


    private fun getComponentName(itemInfo: Any): ComponentName? {
        return runCatching {
            XposedHelpers.getObjectField(itemInfo, "componentName") as? ComponentName
        }.getOrNull() ?: runCatching {
            XposedHelpers.callMethod(itemInfo, "getTargetComponent") as? ComponentName
        }.getOrNull()
    }

    private fun getUserHandle(itemInfo: Any): UserHandle? {
        return runCatching {
            XposedHelpers.getObjectField(itemInfo, "user") as? UserHandle
        }.getOrNull()
    }

    private fun loadOriginalIcon(
        context: Context,
        component: ComponentName,
        user: UserHandle
    ): Drawable? {
        return runCatching {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val activityInfo =
                launcherApps.getActivityList(component.packageName, user).firstOrNull {
                    it.componentName == component
                }
            if (activityInfo != null) return@runCatching activityInfo.getIcon(0)
            context.packageManager.getActivityIcon(component)
        }.getOrNull()
    }

    private fun generateRawThemedBitmap(context: Context, original: Drawable): Bitmap? {
        val isDarkMode =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgColor =
            context.getColor(if (isDarkMode) android.R.color.system_neutral1_800 else android.R.color.system_accent1_100)
        val iconColor =
            context.getColor(if (isDarkMode) android.R.color.system_accent1_100 else android.R.color.system_accent1_600)

        val monoDrawable: Drawable =
            if (original is AdaptiveIconDrawable && original.monochrome != null) {
                original.monochrome!!.mutate()
            } else {
                val size = if (original.intrinsicWidth > 0) original.intrinsicWidth else 108
                GoogleMonochromeIconFactory(original, size).create()
            }

        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(bgColor)

        monoDrawable.setTint(iconColor)
        monoDrawable.setTintMode(PorterDuff.Mode.SRC_IN)

        val scale = 1.50f

        val iconSize = (size * scale).toInt()
        val offset = (size - iconSize) / 2

        monoDrawable.setBounds(offset, offset, offset + iconSize, offset + iconSize)
        monoDrawable.draw(canvas)

        return bitmap
    }

    private class GoogleMonochromeIconFactory(private val icon: Drawable, iconBitmapSize: Int) {
        private val mBitmapSize: Int
        private val mEdgePixelLength: Int
        private val mFlatBitmap: Bitmap
        private val mFlatCanvas: Canvas
        private val mAlphaBitmap: Bitmap
        private val mAlphaCanvas: Canvas
        private val mPixels: ByteArray
        private val mCopyPaint: Paint
        private val mDrawPaint: Paint
        private val mSrcRect: Rect

        init {
            val extraFactor = AdaptiveIconDrawable.getExtraInsetFraction()
            val viewPortScale = 1f / (1 + 2 * extraFactor)
            mBitmapSize = (iconBitmapSize * 2 * viewPortScale).roundToInt()
            mEdgePixelLength = mBitmapSize * (mBitmapSize - iconBitmapSize) / 2
            mPixels = ByteArray(mBitmapSize * mBitmapSize)

            mFlatBitmap = Bitmap.createBitmap(mBitmapSize, mBitmapSize, Bitmap.Config.ARGB_8888)
            mFlatCanvas = Canvas(mFlatBitmap)
            mAlphaBitmap = Bitmap.createBitmap(mBitmapSize, mBitmapSize, Bitmap.Config.ALPHA_8)
            mAlphaCanvas = Canvas(mAlphaBitmap)

            mDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
            mSrcRect = Rect(0, 0, mBitmapSize, mBitmapSize)

            mCopyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                blendMode = BlendMode.SRC
                val satMatrix = ColorMatrix().apply { setSaturation(0f) }
                val vals = satMatrix.array
                vals[15] = 0.3333f; vals[16] = 0.3333f; vals[17] = 0.3333f
                vals[18] = 0f; vals[19] = 0f
                colorFilter = ColorMatrixColorFilter(vals)
            }
        }

        fun create(): Drawable {
            convertMono()
            return BitmapDrawable(null, mAlphaBitmap).apply {
                setBounds(0, 0, mBitmapSize, mBitmapSize)
            }
        }

        private fun convertMono() {
            if (icon is AdaptiveIconDrawable) {
                mFlatCanvas.drawColor(Color.BLACK)
                drawDrawable(icon.background)
                drawDrawable(icon.foreground)
            } else {
                mFlatCanvas.drawColor(Color.WHITE)
                drawDrawable(icon)
            }
            generateMono()
        }

        private fun drawDrawable(drawable: Drawable?) {
            drawable?.apply {
                setBounds(0, 0, mBitmapSize, mBitmapSize)
                draw(mFlatCanvas)
            }
        }

        private fun generateMono() {
            mAlphaCanvas.drawBitmap(mFlatBitmap, 0f, 0f, mCopyPaint)
            val buffer = ByteBuffer.wrap(mPixels)
            buffer.rewind()
            mAlphaBitmap.copyPixelsToBuffer(buffer)

            var min = 0xFF
            var max = 0
            for (b in mPixels) {
                val v = b.toInt() and 0xFF
                if (v < min) min = v
                if (v > max) max = v
            }

            if (min < max) {
                val range = (max - min).toFloat()
                var sum = 0
                for (i in 0 until mEdgePixelLength) {
                    sum += (mPixels[i].toInt() and 0xFF)
                    sum += (mPixels[mPixels.size - 1 - i].toInt() and 0xFF)
                }
                val edgeAverage = sum / (mEdgePixelLength * 2f)
                val edgeMapped = (edgeAverage - min) / range
                val flipColor = edgeMapped > 0.5f

                for (i in mPixels.indices) {
                    val p = mPixels[i].toInt() and 0xFF
                    val p2 = ((p - min) * 0xFF / range).roundToInt()
                    mPixels[i] = (if (flipColor) (255 - p2) else p2).toByte()
                }
                buffer.rewind()
                mAlphaBitmap.copyPixelsFromBuffer(buffer)
            }
        }
    }
}