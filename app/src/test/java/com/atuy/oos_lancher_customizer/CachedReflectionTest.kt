package com.atuy.oos_lancher_customizer

import org.junit.Assert.*
import org.junit.Test

class CachedReflectionTest {
    private open class Parent {
        private val hidden = 42
        private fun answer() = hidden
        private fun echo(value: String) = value
    }
    private class Child : Parent()
    private class Other { private val hidden = "other" }

    @Test fun inheritedPrivateMembersAreFoundOnceAndReused() {
        val field = CachedReflection.field(Child::class.java, "hidden")
        val method = CachedReflection.method(Child::class.java, "answer")
        assertNotNull(field)
        assertNotNull(method)
        repeat(100) {
            assertSame(field, CachedReflection.field(Child::class.java, "hidden"))
            assertSame(method, CachedReflection.method(Child::class.java, "answer"))
        }
        assertEquals(42, CachedReflection.read(Child(), "hidden"))
        assertEquals(42, CachedReflection.call(Child(), "answer"))
        assertEquals("test", CachedReflection.method(Child::class.java, "echo", String::class.java)
            ?.invoke(Child(), "test"))
    }

    @Test fun missingMembersAndDifferentClassesStayIsolated() {
        repeat(100) {
            assertNull(CachedReflection.field(Child::class.java, "absent"))
            assertNull(CachedReflection.method(Child::class.java, "absent"))
        }
        assertEquals("other", CachedReflection.read(Other(), "hidden"))
        assertNull(CachedReflection.read(null, "hidden"))
    }
}
