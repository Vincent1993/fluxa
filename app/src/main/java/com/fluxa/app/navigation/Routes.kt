package com.fluxa.app.navigation

object Routes {
    const val Login = "login"
    const val FeedList = "feedList"
    const val Article = "article/{id}"

    fun article(id: String): String = "article/$id"
}
