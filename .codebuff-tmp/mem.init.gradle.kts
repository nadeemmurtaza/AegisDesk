// TEMPORARY sandbox constraint — caps the forked test worker heap so the
// Gradle daemon + worker fit under the 2 GiB cgroup cap. Deleted after the run.
allprojects {
    tasks.withType<Test>().configureEach {
        maxHeapSize = "384m"
    }
}
