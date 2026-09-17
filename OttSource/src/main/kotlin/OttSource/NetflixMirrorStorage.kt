package OttSource

import android.content.Context
import android.content.SharedPreferences

object NetflixMirrorStorage {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(
            "NetflixMirrorPrefs",
            Context.MODE_PRIVATE
        )
    }

    fun saveCookieHeader(cookieHeader: String) {
        prefs.edit()
            .putString("nf_cookie_header", cookieHeader)
            .putLong("nf_cookie_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun getCookieHeader(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nf_cookie_header", null),
            prefs.getLong("nf_cookie_timestamp", 0L)
        )
    }

    fun clearCookie() {
        prefs.edit()
            .remove("nf_cookie_header")
            .remove("nf_cookie_timestamp")
            .apply()
    }
}