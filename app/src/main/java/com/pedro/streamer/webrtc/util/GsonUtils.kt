package com.pedro.streamer.webrtc.util

import com.google.gson.Gson

object GsonUtils {
    val gson = Gson()

    fun objectToJson(obj: Any): String {
        return try {
            gson.toJson(obj)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    inline fun <reified T> jsonToObject(json: String): T? {
        return try {
            gson.fromJson(json, T::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}