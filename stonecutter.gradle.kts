plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.1"

stonecutter parameters {
    swaps["mod_version"] = "\"${node.project.property("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
    constants["release"] = true
    constants["hdr"] = node.metadata.version !in setOf("1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8")
    dependencies["fapi"] = node.project.property("deps.fabric_api") as String

    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }

        string(current.parsed >= "26.1") {
            replace("classTweaker v2 named", "classTweaker v2 official")
        }
    }
}
