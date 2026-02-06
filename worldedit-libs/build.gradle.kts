tasks.register("build") {
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "build" } })
}
