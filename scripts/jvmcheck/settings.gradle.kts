// Deliberately standalone: this project must NOT see the app's settings file or
// its Android plugins, because resolving those needs dl.google.com, which the
// network policy refuses. Maven Central alone is enough for what runs here.
rootProject.name = "jvmcheck"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
