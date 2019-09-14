package samlang.server

import com.google.gson.Gson
import spark.Spark.get
import spark.Spark.port
import spark.Spark.post
import java.util.concurrent.Executors

fun startServer() {
    val gson = Gson()
    val threadFactory = Executors.defaultThreadFactory()
    port(System.getenv("PORT")?.toIntOrNull() ?: 8080)
    get("/") { _, _ -> "OK" }
    post("/api/respond", { req, _ ->
        WebDemoController.interpret(programString = req.body(), threadFactory = threadFactory)
    }, gson::toJson)
}