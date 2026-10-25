package com.atuy.oos_lancher_customizer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemedSnapshotCacheTest {
    private class Item(val id: Long = 1, val itemType: Int = 0, val container: Int = -100)
    private class CountedDrawable(private val copies: AtomicInteger) : Drawable() {
        private val snapshotState = object : ConstantState() {
            override fun newDrawable(): Drawable {
                copies.incrementAndGet()
                return ColorDrawable(Color.WHITE)
            }
            override fun getChangingConfigurations() = 0
        }
        override fun getConstantState() = snapshotState
        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    @Before fun reset() { ThemedIconPolicy.invalidateTheme() }

    @Test fun repeatedFramesAndMultipleItemsReuseOneDetachedSnapshot() {
        val copies = AtomicInteger()
        val drawable = CountedDrawable(copies)
        repeat(100) { ThemedIconPolicy.rememberDrawable(drawable, Item()) }
        ThemedIconPolicy.rememberDrawable(drawable, Item(2))
        assertEquals(1, copies.get())
        assertSame(ThemedIconPolicy.themedStateFor(Item()), ThemedIconPolicy.themedStateFor(Item(2)))
    }

    @Test fun previewDimmingCannotContaminateTheStoredAnimationSnapshot() {
        val drawable = ColorDrawable(Color.WHITE)
        ThemedIconPolicy.rememberDrawable(drawable, Item())
        val snapshot = ThemedIconPolicy.themedStateFor(Item())
        drawable.mutate().alpha = 30
        repeat(100) { ThemedIconPolicy.rememberDrawable(drawable, Item()) }
        assertSame(snapshot, ThemedIconPolicy.themedStateFor(Item()))
        assertEquals(255, snapshot!!.newDrawable().alpha)
    }

    @Test fun themeChangeInvalidatesSnapshotsButRecognizesOldThemedDrawables() {
        val drawable = ColorDrawable(Color.WHITE)
        ThemedIconPolicy.rememberDrawable(drawable, Item())
        assertTrue(ThemedIconPolicy.isThemedDrawable(drawable))
        ThemedIconPolicy.invalidateTheme()
        assertNull(ThemedIconPolicy.themedStateFor(Item()))
        assertFalse(ThemedIconPolicy.isThemedDrawable(drawable))
        assertTrue(ThemedIconPolicy.isKnownThemedDrawable(drawable))
    }
}
