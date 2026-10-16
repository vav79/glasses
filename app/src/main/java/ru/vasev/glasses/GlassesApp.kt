package ru.vasev.glasses

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import ru.vasev.glasses.glasses.GlassesManager

class GlassesApp : Application() {

    lateinit var glasses: GlassesManager
        private set

    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT init failed: ${error.description}") }
        glasses = GlassesManager(this)
    }

    companion object {
        const val TAG = "Glasses"
    }
}
