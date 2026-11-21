package com.u4mess.ume

import android.app.Application
import com.u4mess.ume.memory.MemoryEntity
import com.u4mess.ume.memory.MemoryRepository
import com.u4mess.ume.memory.MyObjectBox
import io.objectbox.BoxStore

class UmeApplication : Application() {

    lateinit var memoryRepository: MemoryRepository
        private set

    private lateinit var boxStore: BoxStore

    override fun onCreate() {
        super.onCreate()
        boxStore = MyObjectBox.builder().androidContext(this).build()
        memoryRepository = MemoryRepository(boxStore.boxFor(MemoryEntity::class.java))
    }
}
