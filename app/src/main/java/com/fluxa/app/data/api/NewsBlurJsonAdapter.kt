package com.fluxa.app.data.api

import com.squareup.moshi.*

class NewsBlurJsonAdapter {
    @FromJson
    fun feeds(reader: JsonReader, feedAdapter: JsonAdapter<NewsBlurFeed>): NewsBlurFeeds {
        val feeds = linkedMapOf<String, NewsBlurFeed>()
        var code = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "code" -> code = reader.nextInt()
                "feeds" -> if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val id = reader.nextName()
                        feedAdapter.fromJson(reader)?.let { feeds[id] = it }
                    }
                    reader.endObject()
                } else reader.skipValue() // A new/empty account may return [].
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return NewsBlurFeeds(feeds, code)
    }
}
