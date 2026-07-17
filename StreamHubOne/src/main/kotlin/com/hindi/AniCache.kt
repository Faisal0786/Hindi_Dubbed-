package com.hindi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

object AniCache {

    private val cache = HashMap<Int, Pair<Long, String>>()

    private const val EXPIRE = 24L * 60L * 60L * 1000L

    inline fun <reified T> get(id: Int): T? {
        val item = cache[id] ?: return null

        if (System.currentTimeMillis() > item.first) {
            cache.remove(id)
            return null
        }

        return parseJson(item.second)
    }

    fun put(id: Int, value: Any) {
        cache[id] = Pair(
            System.currentTimeMillis() + EXPIRE,
            value.toJson()
        )
    }

    fun clear() = cache.clear()
}