package com.soywiz.landinger.util

class LockSignal {
    private val obj = java.lang.Object()

    @JvmName("wait2")
    fun wait() {
        synchronized(obj) {
            obj.wait()
        }
    }

    @JvmName("notify2")
    fun notify() {
        synchronized(obj) {
            obj.notify()
        }
    }

    @JvmName("notifyAll2")
    fun notifyAll() {
        synchronized(obj) {
            obj.notifyAll()
        }
    }
}
