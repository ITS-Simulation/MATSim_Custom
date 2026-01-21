package com.thomas.pt.utils

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.collections.get
import kotlin.io.path.inputStream

object Utility {
    fun loadYaml(configPath: Path): Map<String, Any>
        = Yaml().load(configPath.inputStream())

    fun getYamlSubconfig(yamlConfig: Map<*, *>, vararg key: String): Map<*, *>
        = key.fold(yamlConfig) { acc, k ->
            acc[k] as? Map<*, *> ?: throw IllegalArgumentException("Missing section '$k' in YAML config")
        }
}