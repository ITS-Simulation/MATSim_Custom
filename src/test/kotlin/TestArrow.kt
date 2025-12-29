import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.filter
import org.jetbrains.kotlinx.dataframe.io.readArrowIPC
import org.jetbrains.kotlinx.dataframe.size

fun main() {
    val linkDf = DataFrame.readArrowIPC("data/temp/link_records.arrow")
        .filter { "is_bus"() }
        .filter { "passenger_load"<Int>() <= 0 }
    println(linkDf.size())
    println(linkDf)
}